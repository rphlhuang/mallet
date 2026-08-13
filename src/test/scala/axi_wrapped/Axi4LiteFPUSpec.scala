// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axi_wrapped

import scala.util.Random
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import axi._
import axi.AxiModuleParamsHelper._
import axi.AxiLiteResp._
import fp._

class Axi4LiteFPUSpec extends AnyFlatSpec with ChiselSim with FP32TestUtil {
  val p = FPUModuleParams.default()
  
  "Axi4LiteFPU" should "handle a single ADD" in {
    simulate(new Axi4LiteFPU(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[Axi4LiteFPU](dut)
      bfm.initMaster()
      bfm.reset()

      val tag = 0
      val a = 3.14f
      val b = 2.71f
      val opcode = FPUOpcode.ADD

      assert(bfm.writeVal(p.tag_w, tag) == 0, f"\nWrite $tag to tag_w failed")
      assert(bfm.writeVal(p.a_w, floatToRawInt(a)) == 0, f"\nWrite $a to a_w failed")
      assert(bfm.writeVal(p.b_w, floatToRawInt(b)) == 0, f"\nWrite $b to b_w failed")
      assert(bfm.writeVal(p.opcode_w, opcode) == 0, "\nWrite opcode ${opName(opcode)} to opcode_w failed")

      // wait for dut by querying outq_cnt_r
      var data = 0
      var polls = 0
      val maxPolls = 100
      do {
        val (d, resp) = bfm.read(p.outq_cnt_r)
        assert(resp != SLVERR.toInt, "\nGot SLVERR while querying outq_cnt_r")
        data = d.toInt
        polls += 1
        assert(polls <= maxPolls, f"\noutq_cnt_r never went to 1 after $maxPolls%d polls")
      } while (data != 1)
      
      assert(bfm.writeVal(p.outq_pop_w, 0) == 0, "\nPop outq_pop_w failed")
      val result_r = bfm.readVal(p.result_r)
      expectHex(result_r, floatToRawInt(a + b), f"\nADD($a, $b)")
    }
  }

}
