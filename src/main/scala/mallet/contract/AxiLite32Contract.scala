// SPDX-License-Identifier: Apache-2.0
package mallet.contract

import chisel3._
import mallet._
import axi.AxiLite32
import axi.AxiLiteResp._

/** The AXI4-Lite slave contract.
  *
  * Direction matters. From the SLAVE's point of view:
  *   - the slave DRIVES awready, wready, bvalid, bresp, arready, rvalid, rdata,
  *     rresp  -> obligations, emitted as ASSERT.
  *   - the master DRIVES awvalid/awaddr, wvalid/wdata/wstrb, bready, arvalid/
  *     araddr, rready  -> environment, emitted as ASSUME.
  *
  * The assumes are not decoration: without them the model checker drives the
  * input channels adversarially and can violate AXI on the master side (e.g.
  * dropping AWVALID mid-handshake), producing spurious counterexamples on the
  * slave's own properties. The assumes constrain the environment to a
  * well-behaved master, which is exactly the setting the slave was designed for.
  *
  * Increment 1 (no auxiliary hardware):
  *   - VALID stability on all five channels: once VALID is asserted it must be
  *     held until the handshake completes. Master-driven VALIDs assumed;
  *     slave-driven VALIDs asserted.
  *   - Response-code legality: AXI4-Lite forbids EXOKAY (no exclusive access).
  *
  * Increment 2 (needs monitor registers, see Monitors):
  *   - Payload stability: address / data / strobe / response must be held stable
  *     while their channel is stalled (VALID && !READY). Master payloads assumed;
  *     slave payloads asserted.
  *   - No unsolicited response: a B/R response only appears while a matching
  *     request is outstanding.
  */
object AxiLite32Contract extends ContractSet[AxiLite32] {

  def name: String = "AXI4-Lite slave"

  def properties(axi: AxiLite32): Seq[NamedProp] = {
    // VALID stability: `valid && !ready |=> valid`.
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
      // ================= Increment 1 =====================================
      // ---- VALID stability: master assumed, slave asserted --------------
      validStable("axi_aw_valid_stable", axi.awvalid, axi.awready, "aw")(NamedProp.assume),
      validStable("axi_w_valid_stable",  axi.wvalid,  axi.wready,  "w")(NamedProp.assume),
      validStable("axi_ar_valid_stable", axi.arvalid, axi.arready, "ar")(NamedProp.assume),
      validStable("axi_b_valid_stable",  axi.bvalid,  axi.bready,  "b")(NamedProp.assert),
      validStable("axi_r_valid_stable",  axi.rvalid,  axi.rready,  "r")(NamedProp.assert),

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

      // ================= Increment 2 =====================================
      // ---- payload stability while stalled: master assumed --------------
      Monitors.stableWhileStalled("axi_awaddr_stable", axi.awvalid, axi.awready, axi.awaddr, "awaddr", NamedProp.assume),
      Monitors.stableWhileStalled("axi_wdata_stable",  axi.wvalid,  axi.wready,  axi.wdata,  "wdata",  NamedProp.assume),
      Monitors.stableWhileStalled("axi_wstrb_stable",  axi.wvalid,  axi.wready,  axi.wstrb,  "wstrb",  NamedProp.assume),
      Monitors.stableWhileStalled("axi_araddr_stable", axi.arvalid, axi.arready, axi.araddr, "araddr", NamedProp.assume),

      // ---- payload stability while stalled: slave asserted --------------
      Monitors.stableWhileStalled("axi_bresp_stable", axi.bvalid, axi.bready, axi.bresp, "bresp", NamedProp.assert),
      Monitors.stableWhileStalled("axi_rdata_stable", axi.rvalid, axi.rready, axi.rdata, "rdata", NamedProp.assert),
      Monitors.stableWhileStalled("axi_rresp_stable", axi.rvalid, axi.rready, axi.rresp, "rresp", NamedProp.assert),

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
