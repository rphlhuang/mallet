package axi_wrapped

import fp.FPUCore
import fp.FPUOpcode

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._

// NOTE: write operands first, then opcode_w writes opcode AND instantly commits to FPUCore
case class FPUModuleParams( // Note: do not put default value here
                            // params suffixed with _r, _w, or _rw represent addresses
                            // DefParams
                            soft_reset_rw : Long,
                            // io: write (write operands first, then opcode_w commits)
                            tag_w    : Long,
                            a_w      : Long,
                            b_w      : Long,
                            opcode_w : Long, 
                            // io: status polling
                            inq_comb_cnt_r : Long,
                            inq_seq_cnt_r  : Long,
                            outq_cnt_r     : Long,
                            // io: read
                            outq_pop_w : Long, // "give me the next result", pops completionQ
                            result_r   : Long,
                            tag_r      : Long,
                            flags_r    : Long,
                            // module params
                            expW_p        : Int,
                            sigW_p        : Int,
                            tagBits_p     : Int,
                            numSeqLanes_p : Int,
                            combQSize_p   : Int,
                            seqQSize_p    : Int,
                            outQSize_p    : Int,
                            // constant definition
                            reset_cycles : Int, // soft reset cycles
                            opcodes : Map[String, Int] // opcode encoding, passed onto cocotb via JSON
                          ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "FPU"
}

object FPUModuleParams {
  implicit val rw: ReadWriter[FPUModuleParams] = macroRW

  def default(expW: Int = 8, sigW: Int = 24, tagBits: Int = 8,
              numSeqLanes: Int = 4, combQSize: Int = 8, seqQSize: Int = 8, outQSize: Int = 16
             ): FPUModuleParams =
    new FPUModuleParams(
      soft_reset_rw = 0x0,
      tag_w = 0x10, a_w = 0x14, b_w = 0x18, opcode_w = 0x1c,
      inq_comb_cnt_r = 0x30, inq_seq_cnt_r = 0x34, outq_cnt_r = 0x38,
      outq_pop_w = 0x40, result_r = 0x44, tag_r = 0x48, flags_r = 0x4c,
      expW_p = expW, sigW_p = sigW, tagBits_p = tagBits, numSeqLanes_p = numSeqLanes,
      combQSize_p = combQSize, seqQSize_p = seqQSize, outQSize_p = outQSize,
      reset_cycles = 8, opcodes = FPUOpcode.byName
    )
}

class Axi4LiteFPU(p : FPUModuleParams, debugprint: Boolean = false)
  extends chisel3.Module with axi.HasAxiLite32IO {

  override val S = IO(new AxiLite32IO())

  val bw = p.expW_p + p.sigW_p

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
  // Instantiate FPUCore
  // -----------------------------

  val softResetPulseReg = RegInit(false.B) // soft reset: pulse when doWrite gets soft_reset_rw
  softResetPulseReg := (doWrite && (awHoldAddrReg === p.soft_reset_rw.U) && (wHoldStrbReg === "b1111".U))
  val combinedReset = (softResetPulseReg || reset.asBool)
  val core = withReset(combinedReset) {
    Module(new FPUCore(p.expW_p, p.sigW_p, p.tagBits_p, p.numSeqLanes_p,
                       p.combQSize_p, p.seqQSize_p, p.outQSize_p))
  }

  // input regs
  val tagStageReg = Reg(UInt(p.tagBits_p.W))
  val aStageReg = Reg(UInt(bw.W))
  val bStageReg = Reg(UInt(bw.W))

  // outputs/result regs
  val popTagReg = Reg(UInt(p.tagBits_p.W))
  val popResultReg = Reg(UInt(bw.W))
  val popFlagsReg  = Reg(UInt(5.W))

  // defaults
  core.io.in.valid := false.B
  core.io.in.bits.tag := tagStageReg
  core.io.in.bits.opcode := 0.U
  core.io.in.bits.a := aStageReg
  core.io.in.bits.b := bStageReg
  core.io.out.ready := false.B

  // -----------------------------
  // Write path: AW -> W and B
  // -----------------------------

  S.AXI.awready := !awHoldValidReg && !bvalidReg
  S.AXI.wready := !wHoldValidReg && !bvalidReg
  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire = S.AXI.wvalid && S.AXI.wready

  when (awFire) {
    awHoldValidReg := true.B
    awHoldAddrReg  := S.AXI.awaddr(19, 0) // in case MMIO range is 1MB
  }

  when (wFire) {
    wHoldValidReg := true.B
    wHoldDataReg  := S.AXI.wdata
    wHoldStrbReg  := S.AXI.wstrb
  }

  when (doWrite) {
    val fullWrite = (wHoldStrbReg === "b1111".U) // only accept word writes
    val a = awHoldAddrReg
    val bresp = WireDefault(OKAY.U)

    when (!fullWrite) {
      bresp := SLVERR.U
    }.otherwise {
      when (a === p.tag_w.U) {
        tagStageReg := wHoldDataReg
      }.elsewhen (a === p.a_w.U) {
        aStageReg := wHoldDataReg
      }.elsewhen (a === p.b_w.U) {
        bStageReg := wHoldDataReg
      }.elsewhen (a === p.opcode_w.U) {
        // opcode write COMMITS the staged command so we read directly
        core.io.in.valid := true.B
        core.io.in.bits.opcode := wHoldDataReg
        // target queue full -> reject
        when (!core.io.in.ready) { 
          bresp := SLVERR.U
        }  
      }.elsewhen (a === p.outq_pop_w.U) {
        // dequeue completionQ head into result regs
        when (core.io.out.valid) {
          core.io.out.ready := true.B
          popTagReg := core.io.out.bits.tag
          popResultReg := core.io.out.bits.result
          popFlagsReg := core.io.out.bits.flags
        }.otherwise {
          bresp := SLVERR.U // pop with empty completionQ
        }
      }.elsewhen (a === p.soft_reset_rw.U) {
        // pass, handled by softResetPulseReg above
      }.otherwise {
        bresp := SLVERR.U
      }
    }
    brespReg := bresp
    bvalidReg := true.B
    awHoldValidReg := false.B
    wHoldValidReg := false.B
  }

  when (bvalidReg && S.AXI.bready) {
    bvalidReg := false.B
  }
  S.AXI.bvalid := bvalidReg
  S.AXI.bresp  := brespReg

  // -----------------------------
  // Read path: AR -> R
  // -----------------------------

  val rdataReg = Reg(UInt(32.W))
  val rvalidReg = RegInit(false.B)
  val rrespReg = RegInit(0.U(2.W))

  // master -AR-> this
  S.AXI.arready := !rvalidReg
  val arFire = S.AXI.arvalid && S.AXI.arready
  when (arFire) {
    rvalidReg := true.B
    rrespReg := OKAY.U
    val a = S.AXI.araddr(19, 0) // 1MB range
    when (a === p.result_r.U) {
      rdataReg := popResultReg
    }.elsewhen (a === p.tag_r.U) {
      rdataReg := popTagReg
    }.elsewhen (a === p.flags_r.U) {
      rdataReg := popFlagsReg
    }.elsewhen (a === p.inq_comb_cnt_r.U) {
      rdataReg := core.io.combCount
    }.elsewhen (a === p.inq_seq_cnt_r.U) {
      rdataReg := core.io.seqCount
    }.elsewhen (a === p.outq_cnt_r.U) {
      rdataReg := core.io.outCount
    }.elsewhen (a === p.soft_reset_rw.U) {
      rdataReg := 0.U
    }.otherwise {
      rrespReg := SLVERR.U
      rdataReg := 0xbad00000L.U
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

object Axi4LiteFPUMain extends App {

  val p = checkParamEnv(
    FPUModuleParams.default(),
    "FPU_MODULE_PARAMS")

  EmitVerilog.generate(new Axi4LiteFPU(p, debugprint=true), p)
}