// SPDX-License-Identifier: Apache-2.0
package mallet.targets

import chisel3._
import _root_.circt.stage.ChiselStage

import mallet._
import axi_wrapped.{Axi4LiteMac, MacModuleParams}

class Canaries(p: MacModuleParams) extends Axi4LiteMac(p) with MalletProperties {

  private val bvalid  = B(S.AXI.bvalid, "bvalid")
  private val awready = B(S.AXI.awready, "awready")
  private val wready  = B(S.AXI.wready, "wready")
  private val dutValid = B(dutValidReg, "dutValidReg")

  // Saturates at 4, so reachCtr === 7 is unrechable
  private val reachCtr = RegInit(0.U(3.W))
  when(reachCtr < 4.U) { reachCtr := reachCtr + 1.U }

  mallet(
    // Vacuous by construction
    NamedProp.assert(
      "no_write_during_bresp",
      bvalid ==> (!awready && !wready),
      "vacuous by construction, but NOT folded -- see the reachability check"
    ),

    // A syntactic tautology (x === x)
    NamedProp.assert(
      "vacuity_canary",
      Always(Cmp(CmpOp.Eq, Sig(dutDataReg, "dutDataReg"), Sig(dutDataReg, "dutDataReg"))),
      "CANARY: x === x, must report VACUOUS or the join is broken"
    ),

    NamedProp.assert(
      "reach_canary",
      Cmp(CmpOp.Eq, Sig(reachCtr, "reachCtr"), Lit(7, "7")) ==> dutValid,
      "CANARY: unreachable antecedent, must report REACH=no / VACUOUS"
    )
  )
}

object CanariesChirrtlMain extends App {
  val p = MacModuleParams.default()

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = false
  ChiselStage.emitCHIRRTLFile(
    new Canaries(p),
    args = Array("--target-dir", "generated/mallet-canaries/chirrtl")
  )
  MalletRegistry.writeSidecar("generated/mallet-canaries/props")

  MalletRegistry.clear()
  MalletRegistry.coversEnabled = true
  ChiselStage.emitCHIRRTLFile(
    new Canaries(p),
    args = Array("--target-dir", "generated/mallet-canaries/reach")
  )
  MalletRegistry.coversEnabled = false
}
