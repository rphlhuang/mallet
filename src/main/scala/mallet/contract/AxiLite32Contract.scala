// SPDX-License-Identifier: Apache-2.0
package mallet.contract

import chisel3._
import mallet._
import axi.AxiLite32
import axi.AxiLiteResp._

object AxiLite32Contract extends ContractSet[AxiLite32] {

  def name: String = "AXI4-Lite slave"

  private val asAssert: (String, Prop, String) => NamedProp = NamedProp.assert(_, _, _)
  private val asAssume: (String, Prop, String) => NamedProp = NamedProp.assume(_, _, _)

  def properties(axi: AxiLite32): Seq[NamedProp] = {
    // VALID stability: valid && !ready |=> valid.
    def validStable(name: String, valid: Bool, ready: Bool, chan: String)
                   (mk: (String, Prop, String) => NamedProp): NamedProp =
      mk(
        name,
        (B(valid, s"${chan}valid") && !B(ready, s"${chan}ready")) |=> B(valid, s"${chan}valid"),
        s"AXI4-Lite: ${chan.toUpperCase}VALID must be held until ${chan.toUpperCase}READY"
      )

    val exokay = Lit(EXOKAY, "EXOKAY")

    // Channel handshake fires, derived purely from the bundle.
    val awFire = axi.awvalid && axi.awready
    val wFire  = axi.wvalid  && axi.wready
    val bFire  = axi.bvalid  && axi.bready
    val arFire = axi.arvalid && axi.arready
    val rFire  = axi.rvalid  && axi.rready

    Seq(
      // ---- VALID stability: master assumed, slave asserted --------------
      validStable("axi_aw_valid_stable", axi.awvalid, axi.awready, "aw")(asAssume),
      validStable("axi_w_valid_stable",  axi.wvalid,  axi.wready,  "w")(asAssume),
      validStable("axi_ar_valid_stable", axi.arvalid, axi.arready, "ar")(asAssume),
      validStable("axi_b_valid_stable",  axi.bvalid,  axi.bready,  "b")(asAssert),
      validStable("axi_r_valid_stable",  axi.rvalid,  axi.rready,  "r")(asAssert),

      // ---- response legality (slave-driven) -----------------------------
      NamedProp.assert(
        "axi_bresp_legal",
        B(axi.bvalid, "bvalid") ==> Cmp(CmpOp.Neq, Sig(axi.bresp, "bresp"), exokay),
        "AXI4-Lite: write response is never EXOKAY (no exclusive access)"
      ),
      NamedProp.assert(
        "axi_rresp_legal",
        B(axi.rvalid, "rvalid") ==> Cmp(CmpOp.Neq, Sig(axi.rresp, "rresp"), exokay),
        "AXI4-Lite: read response is never EXOKAY (no exclusive access)"
      ),

      // ---- payload stability while stalled: master assumed --------------
      Monitors.stableWhileStalled("axi_awaddr_stable", axi.awvalid, axi.awready, axi.awaddr, "awaddr", asAssume),
      Monitors.stableWhileStalled("axi_wdata_stable",  axi.wvalid,  axi.wready,  axi.wdata,  "wdata",  asAssume),
      Monitors.stableWhileStalled("axi_wstrb_stable",  axi.wvalid,  axi.wready,  axi.wstrb,  "wstrb",  asAssume),
      Monitors.stableWhileStalled("axi_araddr_stable", axi.arvalid, axi.arready, axi.araddr, "araddr", asAssume),

      // ---- payload stability while stalled: slave asserted --------------
      Monitors.stableWhileStalled("axi_bresp_stable", axi.bvalid, axi.bready, axi.bresp, "bresp", asAssert),
      Monitors.stableWhileStalled("axi_rdata_stable", axi.rvalid, axi.rready, axi.rdata, "rdata", asAssert),
      Monitors.stableWhileStalled("axi_rresp_stable", axi.rvalid, axi.rready, axi.rresp, "rresp", asAssert),

      // ---- no unsolicited response (slave asserted) ---------------------
      Monitors.writeResponseSolicited(
        "axi_b_solicited", awFire, wFire, axi.bvalid, bFire,
        "AXI4-Lite: a write response only appears while a write is outstanding"
      ),
      Monitors.responseSolicited(
        "axi_r_solicited", arFire, axi.rvalid, rFire, "r",
        "AXI4-Lite: a read response only appears while a read is outstanding"
      )
    )
  }
}
