// SPDX-License-Identifier: Apache-2.0
package mallet.targets

import chisel3._
import _root_.circt.stage.ChiselStage

import mallet._
import axi_wrapped.{Axi4LiteFPAdd, FPAddModuleParams}

class FPAddSpec(p: FPAddModuleParams) extends Axi4LiteFPAdd(p) with MalletSpec {

  // ── memory-map roles ─────────────────────────────────────────────
  p.a_w           is Operand at aReg
  p.b_w           is Operand at bReg
  p.push_w        is Commit  at pushPendingReg requiring (aReg, bReg) acceptedOn dut.io.in.ready
  p.status_r      is Status  at dutValidReg
  p.result_r      is Result  at dutDataReg validWhen dutValidReg
  p.soft_reset_rw is RW

  // ── protocol contract ────────────────────────────────────────────
  S.AXI conformsTo AxiLite32Slave

  // ── raw escape hatch ─────────────────────────────────────────────
  private val srPulse = B(softResetPulseReg, "softResetPulseReg")
  property("soft_reset_is_pulse") { srPulse |=> !srPulse }
  property("result_not_dropped")  { dut.io.out.fire |=> dutValidReg }

  done()
}

object FPAddSpecChirrtlMain extends App {
  val p = FPAddModuleParams.default()

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = false
  ChiselStage.emitCHIRRTLFile(
    new FPAddSpec(p),
    args = Array("--target-dir", "generated/mallet/chirrtl")
  )
  MalletRegistry.writeSidecar("generated/mallet/props")

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = true
  ChiselStage.emitCHIRRTLFile(
    new FPAddSpec(p),
    args = Array("--target-dir", "generated/mallet/reach")
  )
  MalletRegistry.coversEnabled = false
}