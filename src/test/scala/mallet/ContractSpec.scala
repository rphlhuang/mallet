// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import mallet.contract.AxiLite32Contract
import axi.AxiLite32IO

class ContractSpec extends AnyFlatSpec with Matchers {

  private class BareSlave extends Module with MalletProperties {
    val S = IO(new AxiLite32IO())
    // tie outputs off so elaboration is legal
    S.AXI.awready := false.B; S.AXI.wready := false.B
    S.AXI.bvalid := false.B;  S.AXI.bresp := 0.U
    S.AXI.arready := false.B
    S.AXI.rvalid := false.B;  S.AXI.rdata := 0.U; S.AXI.rresp := 0.U
    contract(AxiLite32Contract, S.AXI)
  }

  behavior of "AxiLite32Contract"

  it should "produce the full 16-property set with the right assume/assert split" in {
    MalletRegistry.clear()
    ChiselStage.emitCHIRRTL(new BareSlave)
    val es = MalletRegistry.forModule("BareSlave")

    es.map(_.name) should contain theSameElementsAs Seq(
      "axi_aw_valid_stable", "axi_w_valid_stable", "axi_ar_valid_stable",
      "axi_b_valid_stable", "axi_r_valid_stable",
      "axi_bresp_legal", "axi_rresp_legal",
      "axi_awaddr_stable", "axi_wdata_stable", "axi_wstrb_stable", "axi_araddr_stable",
      "axi_bresp_stable", "axi_rdata_stable", "axi_rresp_stable",
      "axi_b_solicited", "axi_r_solicited"
    )

    es.filter(_.kind == AssumeK).map(_.name) should contain theSameElementsAs Seq(
      "axi_aw_valid_stable", "axi_w_valid_stable", "axi_ar_valid_stable",
      "axi_awaddr_stable", "axi_wdata_stable", "axi_wstrb_stable", "axi_araddr_stable"
    )
    es.filter(_.kind == AssertK) should have length 9
  }

  it should "have monitor-based Increment-2 properties that are combinational (maxPast==0)" in {
    MalletRegistry.clear()
    ChiselStage.emitCHIRRTL(new BareSlave)
    val es = MalletRegistry.forModule("BareSlave")
    Seq("axi_awaddr_stable", "axi_b_solicited", "axi_r_solicited").foreach { n =>
      es.find(_.name == n).get.maxPast shouldBe 0
    }
  }

  it should "observe only the AXI interface (no DUT internals)" in {
    MalletRegistry.clear()
    ChiselStage.emitCHIRRTL(new BareSlave)
    val canon = MalletRegistry.forModule("BareSlave").map(_.prop.canon).mkString(" ")
    canon should include("valid")
    canon should not include "dut"
  }
}
