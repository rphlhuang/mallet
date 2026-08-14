// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axi_wrapped

import scala.util.Random
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import axi._
import axi.AxiModuleParamsHelper._
import axi.AxiLiteResp._

class Axi4LiteTopKSpec extends AnyFlatSpec with ChiselSim {
  val p = TopKModuleParams.default(isFloat_p = false, k_p = 8, valueW_p = 16)

  "Axi4LiteTopK" should "handle five flits" in {
    simulate(new Axi4LiteTopK(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[Axi4LiteTopK](dut)
      bfm.initMaster()
      bfm.reset()

      for (index <- 0 until 5) {
        assert(bfm.writeVal(p.index_w, index) == 0, f"\nWrite $index to index_w failed")
        assert(bfm.writeVal(p.value_w, 5 + index) == 0, f"\nWrite ${5 + index} to value_w failed")
        val isLast = if (index == 4) 1 else 0
        assert(bfm.writeVal(p.last_w, isLast) == 0, f"\nWrite $isLast to last_w failed")
      }

      // wait for dut by querying status_r
      var data = 0
      var polls = 0
      val maxPolls = 100
      do {
        val (d, resp) = bfm.read(p.status_r)
        assert(resp != SLVERR.toInt, "\nGot SLVERR while querying status_r")
        data = d.toInt
        polls += 1
        assert(polls <= maxPolls, f"\nstatus_r never went to 1 after $maxPolls%d polls")
      } while (data != 1)

      var out_val_q = Seq.empty[BigInt]
      var out_idx_q = Seq.empty[BigInt]
      for (i <- 0 until 5) {
        out_idx_q :+= bfm.readVal(p.result_idx_r)
        out_val_q :+= bfm.readVal(p.result_val_r)
      }

      println(s"popped values: $out_val_q")
      println(s"popped indices: $out_idx_q")

      for (i <- 0 until 5) {
        if (i > 0) {
          assert(out_val_q(i) < out_val_q(i-1))
        }
        assert(out_val_q(i) - 5 == out_idx_q(i))
      }

    }
  }

}
