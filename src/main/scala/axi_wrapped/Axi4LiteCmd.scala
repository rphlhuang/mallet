// SPDX-License-Identifier: Apache-2.0
package axi_wrapped

import axi._
import axi.AxiLiteResp._
import axi.AxiModuleParamsHelper._
import upickle.default._

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import mallet.{MalletSpec, MalletRegistry, RO, RW, B}

case class CmdModuleParams( // Note: do not put default value here
                            // params suffixed with _r, _w, or _rw represent addresses
                            // DefParams
                            soft_reset_rw : Long,
                            // module params
                            const1_r : Long, const2_r : Long,
                            dut_rw : Long,
                            // constant definition
                            const1: Long, const2: Long,
                            reset_cycles : Int, // soft reset cycles
                          ) extends AxiModuleParams with AxiModuleDefParams
{
  val moduleName = "Cmd"
}

object CmdModuleParams {
  implicit val rw: ReadWriter[CmdModuleParams] = macroRW

  def default(const1: Long, const2: Long) : CmdModuleParams =
    new CmdModuleParams(
      soft_reset_rw = 0x0, const1_r = 0x10, const2_r = 0x14,  dut_rw = 0x20,
      const1 = const1, const2 = const2, reset_cycles = 8
    )
}

// replace external Verilog for all-Chisel Mallet tests
class CmdDut(bw: Int = 32) extends Module {
  val io = IO(new Bundle {
    val in    = Input(UInt(bw.W))
    val valid = Input(Bool())
    val out   = Output(UInt(bw.W))
  })
  val valReg = RegInit(0.U(bw.W))
  when(io.valid) { valReg := io.in }
  io.out := valReg
}

class Axi4LiteCmd(p : CmdModuleParams, debugprint: Boolean = false)
  extends chisel3.Module with axi.HasAxiLite32IO {

  val bw: Int = 32

  override val S = IO(new AxiLite32IO())

  // cycle counter for convenience
  val (cycles, wrap) = Counter(true.B, 1 << 16)

  // soft reset handling logic for dut
  val softResetReg = RegInit(false.B)
  val softResetDoneReg = RegInit(false.B)
  val resetCounterReg = RegInit(0.U(log2Ceil(p.reset_cycles).W))
  when(resetCounterReg > 0.U) {
    resetCounterReg := resetCounterReg - 1.U
  }.otherwise {
    softResetReg := false.B
    softResetDoneReg := true.B
  }
  val combinedReset = (softResetReg || reset.asBool)

  val dut = withReset(combinedReset) { Module(new CmdDut(bw)) }
  dut.io.in := 0.U
  dut.io.valid := false.B

  // -----------------------------
  // AXI-lite regs
  // -----------------------------
  val awHoldValidReg = RegInit(false.B)
  val awHoldAddrReg = Reg(UInt(bw.W))
  val wHoldValidReg = RegInit(false.B)
  val wHoldDataReg = Reg(UInt(32.W))
  val wHoldStrbReg = Reg(UInt(4.W))

  val bvalidReg = RegInit(false.B)
  val brespReg = RegInit(0.U(2.W))

  S.AXI.awready := !awHoldValidReg && !bvalidReg
  S.AXI.wready := !wHoldValidReg && !bvalidReg
  val awFire = S.AXI.awvalid && S.AXI.awready
  val wFire = S.AXI.wvalid && S.AXI.wready
  when(awFire) {
    awHoldValidReg := true.B;
    awHoldAddrReg := S.AXI.awaddr(19, 0) // in case MMIO range is 1MB
  }
  when(wFire) {
    wHoldValidReg := true.B
    wHoldDataReg := S.AXI.wdata
    wHoldStrbReg := S.AXI.wstrb
  }

  val doWrite = awHoldValidReg && wHoldValidReg && !bvalidReg
  val addrHoldReg = RegInit(0.U(bw.W))

  when(doWrite) {
    val a = awHoldAddrReg
    val fullWrite = (wHoldStrbReg === "b1111".U)

    val bresp = WireDefault(OKAY.U)

    when(!fullWrite) { // support full write only for this example
      bresp := SLVERR.U
    }.elsewhen(a === p.soft_reset_rw.U) {
      softResetReg := true.B
      resetCounterReg := p.reset_cycles.U
      softResetDoneReg := false.B
      bresp := OKAY.U
    }.elsewhen(a === p.dut_rw.U) {
      dut.io.valid := true.B
      dut.io.in := wHoldDataReg
    }.otherwise {
      brespReg := AxiLiteResp.SLVERR.U
    }
    brespReg := bresp
    bvalidReg := true.B
    awHoldValidReg := false.B
    wHoldValidReg := false.B
  }

  when(bvalidReg && S.AXI.bready) {
    bvalidReg := false.B
  }
  S.AXI.bvalid := bvalidReg
  S.AXI.bresp := brespReg

  // -----------------------------
  // Read path: AR -> R
  // -----------------------------
  val rdataReg = Reg(UInt(32.W))
  val rrespReg = RegInit(0.U(2.W))

  object RState extends ChiselEnum {
    val READY2READ, COMPLETED = Value
  }

  val rstateReg = RegInit(RState.READY2READ)

  S.AXI.arready := rstateReg === RState.READY2READ
  S.AXI.rvalid := rstateReg === RState.COMPLETED
  S.AXI.rdata := rdataReg
  S.AXI.rresp := rrespReg

  val arFire = S.AXI.arvalid && S.AXI.arready

  when(arFire) {
    if (debugprint) printf("%d: arFire: %x\n", cycles, S.AXI.araddr)
    val araddr = S.AXI.araddr(19, 0) // 1MB range
    rrespReg := OKAY.U

    val rstate = WireDefault(RState.READY2READ)

    when(araddr === p.const1_r.U) {
      rdataReg := p.const1.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.const2_r.U) {
      rdataReg := p.const2.U
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.soft_reset_rw.U) {
      rdataReg := softResetDoneReg
      rstate := RState.COMPLETED
    }.elsewhen(araddr === p.dut_rw.U) {
      rdataReg := dut.io.out
      rstate := RState.COMPLETED
    }.otherwise {
      if (debugprint) printf("%d: bad read req %d\n", cycles, araddr)
      // rrespReg := SLVERR.U // with this, the host can only read 0xffffffff for any addresses on AVED
      rdataReg := 0xbad00000L.U | S.AXI.araddr(31, 0)
      if (debugprint) printf(cf"arFire otherwise: addr=${S.AXI.araddr}%16x\n")
      rstate := RState.COMPLETED
    }
    rstateReg := rstate
  }

  when(rstateReg === RState.COMPLETED && S.AXI.rready) {
    rstateReg := RState.READY2READ
  }
}

object Axi4LiteCmdMain extends App {
  val const1 : Long = 0xdeadbeefL // module id
  val const2 : Long = getGitHash

  val p = checkParamEnv(
    CmdModuleParams.default(const1 = const1, const2 = const2),
    "CMD_MODULE_PARAMS")

  EmitVerilog.generate(new Axi4LiteCmd(p, debugprint=true), p)
}

class CmdSpec(p: CmdModuleParams) extends Axi4LiteCmd(p) with MalletSpec {

  // ── memory-map roles ─────────────────────────────────────────────
  p.const1_r      is RO
  p.dut_rw        is RW
  p.soft_reset_rw is RW

  // ── protocol contract ────────────────────────────────────────────
  S.AXI conformsTo AxiLite32Slave

  // ── raw escape hatch ─────────────────────────────────────────────
  private val srPulse = B(softResetReg, "softResetPulseReg")
  property("soft_reset_is_pulse") { srPulse |=> !srPulse }

  done()
}

object CmdSpecChirrtlMain extends App {
  val p = CmdModuleParams.default(const1 = 0x42, const2 = 0xdeadbeefL)

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = false
  ChiselStage.emitCHIRRTLFile(
    new CmdSpec(p),
    args = Array("--target-dir", "generated/mallet/chirrtl")
  )
  MalletRegistry.writeSidecar("generated/mallet/props")

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = true
  ChiselStage.emitCHIRRTLFile(
    new CmdSpec(p),
    args = Array("--target-dir", "generated/mallet/reach")
  )
  MalletRegistry.coversEnabled = false
}