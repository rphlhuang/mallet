package axi_wrapped

import rial.ecc.Fletcher

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._

case class FletcherModuleParams( // Note: do not put default value here
                            // params suffixed with _r, _w, or _rw represent addresses
                            // DefParams
                            soft_reset_rw : Long,
                            // module params
                            checksumW_p : Int, // checksum width (16 => Fletcher-16)
                            inputW_p : Int,    // input data width
                            latency_p : Int,   // pipeline latency of the Fletcher core (>= 1)
                            // io
                            data_w : Long,   // write: input data (operand)
                            push_w : Long,   // write: submit data for the checksum, 0b1 if last
                            result_r : Long, // read:  checksum result
                            status_r : Long, // read:  0b1 if result_r is valid
                            // constant definition
                            reset_cycles : Int // soft reset cycles
                          ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "Fletcher"
}

object FletcherModuleParams {
  implicit val rw: ReadWriter[FletcherModuleParams] = macroRW

  def default(checksumW_p: Int = 16, inputW_p: Int = 32, latency_p: Int = 1) : FletcherModuleParams =
    new FletcherModuleParams(
      soft_reset_rw = 0x0, data_w = 0x10, push_w = 0x20, result_r = 0x24, status_r = 0x28,
      checksumW_p = checksumW_p, inputW_p = inputW_p, latency_p = latency_p, reset_cycles = 1
    )
}

class FletcherUnit(checksumW: Int, inputW: Int, latency: Int) extends Module {
  require(latency >= 1, "FletcherUnit needs latency >= 1 to observe the core's delayed out.valid")

  val core = Module(new Fletcher(checksumW, inputW, latency))

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inputW.W)))
    val out = Decoupled(UInt(checksumW.W))
  })

  val busy = RegInit(false.B)
  val full = RegInit(false.B) 
  val resReg = Reg(UInt(checksumW.W))

  io.in.ready := !busy && !full

  core.io.in.valid := io.in.fire
  core.io.in.bits  := io.in.bits
  when(io.in.fire) { busy := true.B }

  // core.out.valid pulses `latency` cycles after the accepted in.valid
  when(busy && core.io.out.valid) {
    resReg := core.io.out.bits
    busy   := false.B
    full   := true.B
  }

  io.out.valid := full
  io.out.bits  := resReg
  when(io.out.fire) { full := false.B }
}

class Axi4LiteFletcher(p : FletcherModuleParams, debugprint: Boolean = false)
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
  // Instantiate Fletcher DUT and regs
  // -----------------------------

  val softResetPulseReg = RegInit(false.B) // soft reset: when doWrite and write addr
  softResetPulseReg := (doWrite && (awHoldAddrReg === p.soft_reset_rw.U) && (wHoldStrbReg === "b1111".U))
  val combinedReset = (softResetPulseReg || reset.asBool) // reset if whole module reset OR dut-only soft reset
  val dut = withReset(combinedReset) {Module(new FletcherUnit(p.checksumW_p, p.inputW_p, p.latency_p))}

  val dataReg = Reg(UInt(p.inputW_p.W))
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
      when(a === p.data_w.U) {
        dataReg := wHoldDataReg
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

  // push the pending data into the DUT once it accepts
  dut.io.in.valid := pushPendingReg
  dut.io.in.bits  := dataReg
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

  val dutDataReg = Reg(UInt(p.checksumW_p.W))
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

object Axi4LiteFletcherMain extends App {

  val p = checkParamEnv(
    FletcherModuleParams.default(),
    "FLETCHER_MODULE_PARAMS")

  EmitVerilog.generate(new Axi4LiteFletcher(p, debugprint=true), p)
}
