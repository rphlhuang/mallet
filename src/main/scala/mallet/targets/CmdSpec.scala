// SPDX-License-Identifier: Apache-2.0
package mallet.targets

import chisel3._
import _root_.circt.stage.ChiselStage

import mallet._
import axi_examples.{Axi4Lite32Cmd, CmdModuleParams}

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

class CmdSpec(p: CmdModuleParams) extends Axi4Lite32Cmd(p) with MalletSpec {

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
  concretizeDut("generated/mallet/chirrtl/CmdSpec.fir")
  MalletRegistry.writeSidecar("generated/mallet/props")

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = true
  ChiselStage.emitCHIRRTLFile(
    new CmdSpec(p),
    args = Array("--target-dir", "generated/mallet/reach")
  )
  concretizeDut("generated/mallet/reach/CmdSpec.fir")
  MalletRegistry.coversEnabled = false

  // temp workaround [Claude]
  def concretizeDut(firPath: String): Unit = {
    val path = Paths.get(firPath)
    var src  = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    // Drop the BlackBoxPathAnno object (and its preceding comma) from the header.
    src = src.replaceAll("(?s),\\s*\\{[^{}]*BlackBoxPathAnno[^{}]*\\}", "")

    // Swap `extmodule Dut : ... parameter BW = 32` for a real module with a body.
    val concrete =
      """  module Dut :
        |    input clock : Clock
        |    input reset : UInt<1>
        |    input in : UInt<32>
        |    input valid : UInt<1>
        |    output out : UInt<32>
        |
        |    reg valReg : UInt<32>, clock
        |    connect valReg, mux(reset, UInt<32>(0h0), mux(valid, in, valReg))
        |    connect out, valReg""".stripMargin
    src = src.replaceAll("(?s)  extmodule Dut :.*?\\n    parameter BW = 32", concrete)

    Files.write(path, src.getBytes(StandardCharsets.UTF_8))
    println(s"[mallet] concretized Dut in $firPath")
  }
}