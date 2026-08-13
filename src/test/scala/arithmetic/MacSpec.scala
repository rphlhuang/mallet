// SPDX-License-Identifier: Apache-2.0
package arithmetic

import scala.util.Random

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class MacSpec extends AnyFlatSpec with ChiselSim {
  val DEFAULT_WIDTH = 8
  val DEFAULT_ACCWIDTH = 32
  val NUM_RANDOM_TESTS = 100
  val TIMEOUT = 100

  val max = (1 << DEFAULT_WIDTH) - 1
  val rng = new Random(42L)

  def enqueue(dut: Mac, a: Int, b: Int, last: Boolean): Unit = {
    dut.io.in.bits.a.poke(a.U)
    dut.io.in.bits.b.poke(b.U)
    dut.io.in.bits.last.poke(last.B)
    dut.io.in.valid.poke(true.B)
    while (!dut.io.in.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  def dequeue(dut: Mac, expected: Int): Unit = {
    dut.io.out.ready.poke(true.B)
    var cycle_count = 0
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step()
      cycle_count += 1
      assert(cycle_count <= TIMEOUT, s"\ndequeue timed out after $TIMEOUT cycles")
    }
    dut.io.out.bits.expect(expected.U)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  "Mac" should "not produce outputs without handshake" in {
    simulate(new Mac(width=DEFAULT_WIDTH, accWidth=DEFAULT_ACCWIDTH)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step(5)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "not produce outputs when not finished" in {
    simulate(new Mac(width=DEFAULT_WIDTH, accWidth=DEFAULT_ACCWIDTH)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.a.poke(2.U)
      dut.io.in.bits.b.poke(4.U)
      dut.io.in.bits.last.poke(false.B)
      dut.clock.step(3)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "handle single cycle multiplications" in {
    simulate(new Mac(width=DEFAULT_WIDTH, accWidth=DEFAULT_ACCWIDTH)) { dut =>
      enqueue(dut, 2, 2, true)
      dequeue(dut, 4)
    }
  }

  it should "handle a series of the same multiplication" in {
    simulate(new Mac(width=DEFAULT_WIDTH, accWidth=DEFAULT_ACCWIDTH)) { dut =>
      val num_cycles = 10
      for (i <- 0 until (num_cycles - 1)) {
        enqueue(dut, 2, 2, false)
      }
      enqueue(dut, 2, 2, true)
      dequeue(dut, (4 * num_cycles))
    }
  }
  
  it should "handle a series of random multiplications" in {
    simulate(new Mac(width=DEFAULT_WIDTH, accWidth=DEFAULT_ACCWIDTH)) { dut =>
      for (num_series <- 0 until 10){
        var expected = 0
        val num_cycles = 10
        for (i <- 0 until (num_cycles - 1)) {
          var a = rng.between(0, max)
          var b = rng.between(0, max)
          enqueue(dut, a, b, false)
          expected += a * b
        }
        var a = rng.between(0, max)
        var b = rng.between(0, max)
        enqueue(dut, a, b, true)
        expected += a * b
        dequeue(dut, expected)
      }

    }
  }
}
