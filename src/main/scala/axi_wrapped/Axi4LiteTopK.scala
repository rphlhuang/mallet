package axi_wrapped

// Streaming Top-K / argmax 
// float vs int comparison is chosen at compile-time:
//   - float path -> hardfloat CompareRecFN compares recoded IEEE values directly
//   - int path   -> plain integer comparison on the raw value

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import hardfloat._

import mallet.{MalletSpec, MalletRegistry, Operand, Status, Result, Commit, RW, B}

case class TopKModuleParams( // Note: do not put default value here
                            // params suffixed with _r, _w, or _rw represent addresses
                            // DefParams
                            soft_reset_rw : Long,
                            // module params
                            k_p       : Int,     // number of top elements kept == register-file depth
                            isFloat_p : Boolean, // FP vs raw integer compare
                            valueW_p  : Int,     // width of each value
                            expW_p    : Int,     // float path only: exponent width (fp16=5, bf16=8, fp32=8)
                            sigW_p    : Int,     // float path only: significand width incl. hidden bit (fp16=11, bf16=8, fp32=24)
                            indexW_p  : Int,     // width of index/tag
                            // io (write)
                            index_w   : Long, // write this first
                            value_w   : Long, // writing this also commits the (i, v) pair; WRITE THIS SECOND
                            last_w    : Long, // writing this finalizes the set
                            // io (read)
                            result_val_r : Long, // pop next top-K value, descending (first is argmax)
                            result_idx_r : Long, // read index paired with the most-recent popped value (doesn't pop)
                            status_r     : Long, // bit0 = result available / stream finalized
                            // constant definition
                            reset_cycles : Int // soft reset cycles
                          ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "TopK"
}

object TopKModuleParams {
  implicit val rw: ReadWriter[TopKModuleParams] = macroRW

  // defaults to the int path
  def default(isFloat_p: Boolean = false, k_p: Int = 8, valueW_p: Int = 16,
              expW_p: Int = 5, sigW_p: Int = 11, indexW_p: Int = 16) : TopKModuleParams =
    new TopKModuleParams(
      soft_reset_rw = 0x0,
      value_w = 0x10, index_w = 0x14, last_w = 0x20,
      result_val_r = 0x24, result_idx_r = 0x28, status_r = 0x2c,
      k_p = k_p, isFloat_p = isFloat_p, valueW_p = valueW_p,
      expW_p = expW_p, sigW_p = sigW_p, indexW_p = indexW_p, reset_cycles = 1
    )
}

class TopKUnit(k: Int, valueW: Int, indexW: Int, isFloat: Boolean) extends Module {
  
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Bundle {
      val value = UInt(valueW.W)
      val index = UInt(indexW.W)
      val last = Bool()
    }))
    val out = Decoupled(new Bundle {
      val value = UInt(valueW.W)
      val index = UInt(indexW.W)
    })
  })

  // create slot registers
  class SlotEntry(valueW: Int, indexW: Int, cmpW: Int) extends Bundle {
    val value = UInt(valueW.W)
    val index = UInt(indexW.W)
    val cmpValue = UInt(cmpW.W)
  }
  val cmpWidth = if (isFloat) (valueW + 1) else valueW
  val slots = Reg(Vec(k, new SlotEntry(valueW, indexW, cmpWidth)))
  val cmpInitVal = 0.U(cmpWidth.W)// NEED TO CHANGE FOR FLOATS
  val valueInitVal = 0.U(valueW.W)
  val indexInitVal = 0.U(indexW.W)
  when (reset.asBool) { 
    for (i <- 0 until k) { 
      slots(i).value := valueInitVal
      slots(i).cmpValue := cmpInitVal
      slots(i).index := indexInitVal
    } 
  } 

  // don't dispense until last pair has been ordered, and "last" signal is 1
  val streamDoneReg = RegInit(false.B)
  when (io.in.fire && io.in.bits.last) { streamDoneReg := true.B }

  // determine which slot this current input is greater than
  val gt = Wire(Vec(k, Bool()))
  val curCmpValue = Wire(UInt(cmpWidth.W))
  if (isFloat) {
    // curCmpValue :=
    // val recIn = recFNFromFN(expW, sigW, io.in.bits.value)
    // val cmp = Seq.fill(k)(Module(new CompareRecFN(expW, sigW)))
  } else {
    curCmpValue := io.in.bits.value
    for (i <- 0 until k) {
      gt(i) := io.in.bits.value > slots(i).cmpValue
    }
  }

  // when accepting, move everything over and insert into slot
  val insertSlot = PriorityEncoder(gt)
  when (io.in.fire) {
    // if greater than smallest slot, schmove everything, maneesh on the beat shabang
    when (gt(k-1)) {
      for (i <- 0 until k) {
        when (i.U === insertSlot) {
          val newSlotEntry = Wire(new SlotEntry(valueW, indexW, cmpWidth))
          newSlotEntry.value := io.in.bits.value
          newSlotEntry.index := io.in.bits.index
          newSlotEntry.cmpValue := curCmpValue
          slots(i) := newSlotEntry
        }.elsewhen (gt(i)) {
          slots(i) := slots(if (i > 0) i - 1 else 0)
        }.otherwise {
          slots(i) := slots(i)
        }
      }
    }
  }

  // downstream read -> pop
  when (io.out.fire) {
    for (i <- 0 until k-1) {
      slots(i) := slots(i+1)
    }
    val newSlotEntry = Wire(new SlotEntry(valueW, indexW, cmpWidth))
    newSlotEntry.cmpValue := cmpInitVal
    newSlotEntry.value := valueInitVal
    newSlotEntry.index := indexInitVal
    slots(k-1) := newSlotEntry
  }
  io.out.bits.value := slots(0).value
  io.out.bits.index := slots(0).index

  io.in.ready := !io.out.fire
  io.out.valid := (slots(0).value =/= valueInitVal) && streamDoneReg
}




class Axi4LiteTopK(p : TopKModuleParams, debugprint: Boolean = false)
  extends chisel3.Module with axi.HasAxiLite32IO {

  override val S = IO(new AxiLite32IO())
  require(!p.isFloat_p || p.valueW_p == (p.expW_p + p.sigW_p), f"Fatal error: expW_p + sigW_p must equal valueW_p (expected ${(p.expW_p + p.sigW_p)}, got ${p.valueW_p})")

  // -----------------------------
  // AXI-lite regs
  // -----------------------------

  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg = Reg(UInt(32.W))
  val wHoldValidReg = RegInit(false.B)
  val wHoldDataReg = Reg(UInt(32.W))
  val wHoldStrbReg = Reg(UInt(4.W))

  val bvalidReg = RegInit(false.B)
  val brespReg = RegInit(0.U(2.W))

  val doWrite = awHoldValidReg && wHoldValidReg && !bvalidReg

  // -----------------------------
  // Instantiate TopK DUT and regs
  // -----------------------------

  val softResetPulseReg = RegInit(false.B) // soft reset: when doWrite and write addr
  softResetPulseReg := (doWrite && (awHoldAddrReg === p.soft_reset_rw.U) && (wHoldStrbReg === "b1111".U))
  val combinedReset = (softResetPulseReg || reset.asBool) // reset if whole module reset OR dut-only soft reset
  val dut = withReset(combinedReset) {Module(new TopKUnit(p.k_p, p.valueW_p, p.indexW_p, p.isFloat_p))}

  val dataReg = Reg(UInt(p.valueW_p.W))
  val indexReg = Reg(UInt(p.indexW_p.W))
  val pushPendingReg = RegInit(false.B)
  val lastReg = RegInit(false.B)

  // -----------------------------
  // Write path: AW -> W and B
  // -----------------------------

  S.AXI.awready := !awHoldValidReg && !bvalidReg && !pushPendingReg
  S.AXI.wready := !wHoldValidReg && !bvalidReg && !pushPendingReg
  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire = S.AXI.wvalid && S.AXI.wready

  when (awFire) {
    awHoldValidReg := true.B;
    awHoldAddrReg := S.AXI.awaddr(19, 0) // in case MMIO range is 1MB
  }

  when (wFire) {
    wHoldValidReg := true.B
    wHoldDataReg := S.AXI.wdata
    wHoldStrbReg := S.AXI.wstrb
  }

  when (doWrite) {

    val fullWrite = (wHoldStrbReg === "b1111".U) // only accept word writes
    val a = awHoldAddrReg
    val bresp = WireDefault(OKAY.U)

    when (!fullWrite) {
      bresp := SLVERR.U // support full write only for this example
    }.otherwise {
      when(a === p.index_w.U) {
        indexReg := wHoldDataReg
      }.elsewhen(a === p.value_w.U) {
        dataReg := wHoldDataReg
      }.elsewhen(a === p.last_w.U) {
        // if last_w is 1, push and last; else just push
        pushPendingReg := true.B
        lastReg := wHoldDataReg(0).asBool
      }.elsewhen(a === p.soft_reset_rw.U) {
        // pass, handled by softResetPulseReg assign above
      }.otherwise {
        bresp := SLVERR.U
      }
    }
    brespReg := bresp
    bvalidReg := true.B
    awHoldValidReg := false.B
    wHoldValidReg := false.B

  }

  // push the pending data into the DUT once it accepts
  dut.io.in.valid := pushPendingReg
  dut.io.in.bits.value := dataReg
  dut.io.in.bits.index := indexReg
  dut.io.in.bits.last  := lastReg
  when (pushPendingReg && dut.io.in.fire) {
    pushPendingReg := false.B
  }

  // B resp reset
  when (bvalidReg && S.AXI.bready) {
    bvalidReg := false.B
  }
  S.AXI.bvalid := bvalidReg
  S.AXI.bresp := brespReg


  // -----------------------------
  // Read path: AR -> R
  // -----------------------------

  val rdataReg = Reg(UInt(32.W))
  val rvalidReg = RegInit(false.B)
  val rrespReg = RegInit(0.U(2.W))

  val dutDataReg = Reg(UInt(p.valueW_p.W))
  val dutIdxReg = Reg(UInt(p.indexW_p.W))
  val dutValidReg = RegInit(false.B)

  // this <-> dut
  // backpressure on dut, accept on handshake
  dut.io.out.ready := !dutValidReg
  when (dut.io.out.fire) {
    dutDataReg := dut.io.out.bits.value
    dutIdxReg := dut.io.out.bits.index
    dutValidReg := true.B
  }

  // master -AR-> this
  S.AXI.arready := !rvalidReg
  val arFire = S.AXI.arvalid && S.AXI.arready
  when (arFire) {
    rvalidReg := true.B
    rrespReg := OKAY.U
    val a = S.AXI.araddr(19, 0)
    when (a === p.result_val_r.U) {
      rdataReg := dutDataReg
      dutValidReg := false.B // consumed; lets the DUT advance to the next kept slot
    }.elsewhen (a === p.result_idx_r.U) {
      rdataReg := dutIdxReg // paired with the most-recently popped value; does not pop
    }.elsewhen (a === p.status_r.U) {
      rdataReg := dutValidReg
    }.otherwise {
      rrespReg := SLVERR.U
    }
  }

  // master <-R- this
  S.AXI.rvalid := rvalidReg
  S.AXI.rdata := rdataReg
  S.AXI.rresp := rrespReg
  val rFire = S.AXI.rvalid && S.AXI.rready
  when (rFire) {
    rvalidReg := false.B
  }
}

object Axi4LiteTopKMain extends App {

  val p = checkParamEnv(
    TopKModuleParams.default(),
    "TOPK_MODULE_PARAMS")

  EmitVerilog.generate(new Axi4LiteTopK(p, debugprint=true), p)
}

class TopKSpec(p: TopKModuleParams) extends Axi4LiteTopK(p) with MalletSpec {

  // ── memory-map roles ─────────────────────────────────────────────
  p.index_w       is Operand at indexReg
  p.value_w       is Operand at dataReg 
  p.last_w        is Commit  at pushPendingReg requiring (indexReg, dataReg) acceptedOn dut.io.in.ready
  p.result_val_r  is Result  at dutDataReg validWhen dutValidReg
  p.result_idx_r  is Result  at dutIdxReg validWhen dutValidReg
  p.status_r      is Status  at dutValidReg
  p.soft_reset_rw is RW

  // ── protocol contract ────────────────────────────────────────────
  S.AXI conformsTo AxiLite32Slave

  // ── raw escape hatch ─────────────────────────────────────────────
  private val srPulse = B(softResetPulseReg, "softResetPulseReg")
  property("soft_reset_is_pulse") { srPulse |=> !srPulse }
  property("result_not_dropped")  { dut.io.out.fire |=> dutValidReg }

  done()
}

object TopKSpecChirrtlMain extends App {
  val p = TopKModuleParams.default()

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = false
  ChiselStage.emitCHIRRTLFile(
    new TopKSpec(p),
    args = Array("--target-dir", "generated/mallet/chirrtl")
  )
  MalletRegistry.writeSidecar("generated/mallet/props")

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = true
  ChiselStage.emitCHIRRTLFile(
    new TopKSpec(p),
    args = Array("--target-dir", "generated/mallet/reach")
  )
  MalletRegistry.coversEnabled = false
}