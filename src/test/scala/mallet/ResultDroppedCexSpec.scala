// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import axi._
import axi_wrapped.{Axi4LiteMac, MacModuleParams}

class ResultDroppedCexSpec extends AnyFlatSpec with ChiselSim {

  val p = MacModuleParams.default(width_p = 8, accWidth_p = 32)

  "Axi4LiteMac" should "not drop a MAC result when result_r is read on the completion cycle" ignore {
    simulate(new Axi4LiteMac(p)) { d =>
      val bfm = new Axi4Lite32BFM[Axi4LiteMac](d)
      bfm.initMaster()
      bfm.reset()

      // Stage and commit a single 3*4 multiply.
      assert(bfm.writeVal(p.a_w, 3) == 0)
      assert(bfm.writeVal(p.b_w, 4) == 0)
      assert(bfm.writeVal(p.push_w, 1) == 0)

      var t = 0
      while (!d.dut.io.out.valid.peekBoolean() && t < 50) { bfm.step(1); t += 1 }
      assert(d.dut.io.out.valid.peekBoolean(), "MAC never produced a result")
      assert(!d.dutValidReg.peekBoolean(), "precondition: no result latched yet")

      d.S.AXI.araddr.poke(p.result_r.U)
      d.S.AXI.arvalid.poke(true.B)
      d.S.AXI.rready.poke(true.B)
      bfm.step(1)
      d.S.AXI.arvalid.poke(false.B)
      bfm.step(1)
      d.S.AXI.rready.poke(false.B)
      bfm.step(1)

      var polls = 0
      var status = 0
      while (status != 1 && polls < 50) {
        status = bfm.read(p.status_r)._1.toInt
        polls += 1
      }

      assert(
        status == 1,
        "MAC result was silently dropped: the result was accepted from the MAC " +
          "(out.fire) but dutValidReg was cleared by the concurrent result_r read, " +
          "so status_r never goes high and the value is unrecoverable."
      )
      bfm.expectVal(p.result_r, 12)
    }
  }
}
