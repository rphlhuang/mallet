package axi_wrapped

import fp._

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._

case class FPAddModuleParams( // Note: do not put default value here
                            // params suffixed with _r, _w, or _rw represent addresses
                            // DefParams
                            soft_reset_rw : Long,
                            // module params
                            width_p : Int,    // operand width  (expW + sigW, 32 for FP32)
                            accWidth_p : Int, // result width   (32 for FP32)
                            // io
                            a_w : Long,      // write: operand a
                            b_w : Long,      // write: operand b
                            push_w : Long,   // write: submit a and b for the add, 0b1 if last
                            result_r : Long, // read:  result of the add
                            status_r : Long, // read:  0b1 if result_r is valid
                            // constant definition
                            reset_cycles : Int // soft reset cycles
                          ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "FPAdd"
}

object FPAddModuleParams {
  implicit val rw: ReadWriter[FPAddModuleParams] = macroRW

  // FP32 single precision: 8 exp + 24 sig = 32-bit operands and result.
  def default(width_p: Int = 32, accWidth_p: Int = 32) : FPAddModuleParams =
    new FPAddModuleParams(
      soft_reset_rw = 0x0, a_w = 0x10, b_w = 0x14, push_w = 0x20, result_r = 0x24, status_r = 0x28,
      width_p = width_p, accWidth_p = accWidth_p, reset_cycles = 1
    )
}

/** Decoupled input bundle for [[FPAddUnit]]: operands `a`, `b` and an (unused here) `last` flag. */
class FPAddIn(val bw: Int) extends Bundle {
  val a    = UInt(bw.W)
  val b    = UInt(bw.W)
  val last = Bool()
}

class FPAddUnit(expW: Int = 8, sigW: Int = 24) extends Module {
  val bw   = expW + sigW
  val core = Module(new FPCombUnit(expW, sigW, FPOpMode.ADD))

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new FPAddIn(bw)))
    val out = Decoupled(UInt(bw.W))
  })

  val busy   = RegInit(false.B)
  val resReg = Reg(UInt(bw.W))

  core.io.in_a := io.in.bits.a
  core.io.in_b := io.in.bits.b

  io.in.ready := !busy
  when(io.in.fire) {
    resReg := core.io.out
    busy   := true.B
  }

  io.out.valid := busy
  io.out.bits  := resReg
  when(io.out.fire) {
    busy := false.B
  }
}

class Axi4LiteFPAdd(p : FPAddModuleParams, debugprint: Boolean = false)
  extends chisel3.Module with axi.HasAxiLite32IO {

  override val S = IO(new AxiLite32IO())

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
  // Instantiate FPAdd DUT and regs
  // -----------------------------

  val softResetPulseReg = RegInit(false.B) // soft reset: when doWrite and write addr
  softResetPulseReg := (doWrite && (awHoldAddrReg === p.soft_reset_rw.U) && (wHoldStrbReg === "b1111".U))
  val combinedReset = (softResetPulseReg || reset.asBool) // reset if whole module reset OR dut-only soft reset
  val dut = withReset(combinedReset) {Module(new FPAddUnit())}

  val aReg = Reg(UInt(p.width_p.W))
  val bReg = Reg(UInt(p.width_p.W))
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
      // if not strobe or reset, write to internal reg or start calc
      when(a === p.a_w.U) {
        aReg := wHoldDataReg
      }.elsewhen(a === p.b_w.U) {
        bReg := wHoldDataReg
      }.elsewhen(a === p.push_w.U) {
        // if push_w is 1, push and last; else just push
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

  // when downstream ready and we have valid data, fire downstream write
  dut.io.in.valid := pushPendingReg
  dut.io.in.bits.a := aReg
  dut.io.in.bits.b := bReg
  dut.io.in.bits.last := lastReg
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

  val dutDataReg = Reg(UInt(p.accWidth_p.W))
  val dutValidReg = RegInit(false.B)

  // this <-> dut
  // backpressure on dut, accept on handshake
  dut.io.out.ready := !dutValidReg
  when (dut.io.out.fire) {
    dutDataReg := dut.io.out.bits
    dutValidReg := true.B
  }

  // master -AR-> this
  S.AXI.arready := !rvalidReg
  val arFire = S.AXI.arvalid && S.AXI.arready
  when (arFire) {
    rvalidReg := true.B
    rrespReg := OKAY.U
    val a = S.AXI.araddr(19, 0)
    when (a === p.result_r.U) {
      rdataReg := dutDataReg
      dutValidReg := false.B
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

object Axi4LiteFPAddMain extends App {

  val p = checkParamEnv(
    FPAddModuleParams.default(),
    "FPADD_MODULE_PARAMS")

  EmitVerilog.generate(new Axi4LiteFPAdd(p, debugprint=true), p)
}
