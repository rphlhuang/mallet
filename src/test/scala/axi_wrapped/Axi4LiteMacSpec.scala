// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package axi_wrapped

import scala.util.Random

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import axi._
import axi.AxiModuleParamsHelper._
import axi.AxiLiteResp._

class Axi4LiteMacSpec extends AnyFlatSpec with ChiselSim {

  val p = MacModuleParams.default(width_p = 8, accWidth_p = 32)
  val rng = new Random(42L)
  // 2^32 = x * ((2^8)-1 * ((2^8)-1)
  val CYCLES_UNTIL_POSSIBLE_OVERFLOW = ((1L << 32) / (((1 << 8) - 1) * ((1 << 8) - 1))).toLong - 1

  def constantTest(bfm: Axi4Lite32BFM[Axi4LiteMac], a: Int, b: Int, cycles: Int = 1): Unit = {
      if (cycles > 1) {
        for (i <- 0 until (cycles - 1)) {
          assert(bfm.writeVal(p.a_w, a) == 0, f"\nWrite $a to a_w failed")
          assert(bfm.writeVal(p.b_w, b) == 0, f"\nWrite $b to b_w failed")
          assert(bfm.writeVal(p.push_w, 0) == 0, "\nWrite push (0) to push_w failed")
        }
      }
      assert(bfm.writeVal(p.b_w, b) == 0, f"\nWrite $b to b_w failed")
      assert(bfm.writeVal(p.a_w, a) == 0, f"\nWrite $a to a_w failed")
      assert(bfm.writeVal(p.push_w, 1) == 0, "\nWrite last (1) to push_w failed")

      // wait for dut by querying status_r
      var data = 0
      var polls = 0
      val maxPolls = 100
      do {
        val (d, resp) = bfm.read(p.status_r)
        assert(resp != SLVERR.toInt, "\nGot SLVERR while waiting for status_r")
        data = d.toInt
        polls += 1
        assert(polls <= maxPolls, f"\nstatus_r never went high after $maxPolls%d polls")
      } while (data != 1)

      bfm.expectVal(p.result_r, cycles * (a * b))
  }

  def fuzzTest(bfm: Axi4Lite32BFM[Axi4LiteMac], min: Int, max: Int, num_tests: Int, max_cycles: Int = 50): Unit = {
    for (test <- 0 until num_tests) {
      var expected = 0L
      val cycles = rng.between(1, max_cycles)
      for (i <- 0 until (cycles - 1)) {
        val a = rng.between(min, max)
        val b = rng.between(min, max)
        assert(bfm.writeVal(p.a_w, a) == 0, f"\nWrite $a to a_w failed")
        assert(bfm.writeVal(p.b_w, b) == 0, f"\nWrite $b to b_w failed")
        assert(bfm.writeVal(p.push_w, 0) == 0, "\nWrite push (0) to push_w failed")
        expected += (a * b)
      }

      val a = rng.between(min, max)
      val b = rng.between(min, max)
      assert(bfm.writeVal(p.b_w, b) == 0, f"\nWrite $b to b_w failed")
      assert(bfm.writeVal(p.a_w, a) == 0, f"\nWrite $a to a_w failed")
      assert(bfm.writeVal(p.push_w, 1) == 0, "\nWrite last (1) to push_w failed")
      expected += (a * b)

      // wait for dut by querying status_r
      var data = 0
      var polls = 0
      val maxPolls = 100
      do {
        val (d, resp) = bfm.read(p.status_r)
        assert(resp != SLVERR.toInt, "\nGot SLVERR while waiting for status_r")
        data = d.toInt
        polls += 1
        assert(polls <= maxPolls, f"\nstatus_r never went high after $maxPolls%d polls")
      } while (data != 1)

      bfm.expectVal(p.result_r, expected)
    }
  }

  "Axi4LiteMac" should "handle a single multiply" in {
    simulate(new Axi4LiteMac(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[Axi4LiteMac](dut)
      bfm.initMaster()
      bfm.reset()
      constantTest(bfm, 3, 4)
    }
  }

  it should "handle a simple multi-flit accumulate" in {
    simulate(new Axi4LiteMac(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[Axi4LiteMac](dut)
      bfm.initMaster()
      bfm.reset()
      constantTest(bfm, 3, 4, 5)
    }
  }

  it should "handle a random cycle-length multi-flit accumulates" in {
    simulate(new Axi4LiteMac(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[Axi4LiteMac](dut)
      bfm.initMaster()
      bfm.reset()
      fuzzTest(bfm, 0, 10, 50)
    }
  }

  it should "handle large multi-flit accumulates" in {
    simulate(new Axi4LiteMac(p, debugprint = true)) { dut =>
      val bfm = new Axi4Lite32BFM[Axi4LiteMac](dut)
      bfm.initMaster()
      bfm.reset()
      fuzzTest(bfm, (1 << 6), (1 << 7), 2, 5000)
    }
  }
}
