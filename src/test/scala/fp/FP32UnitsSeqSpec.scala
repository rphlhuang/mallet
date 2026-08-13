// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package fp

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

// Golden reference: Java Float arithmetic is IEEE-754 single-precision round-nearest-even,
// canonical NaN result becomes 0x7FC00000 which is what the NaN hardfloat/RISCV produce.

class FP32UnitsSeqSpec extends AnyFlatSpec with ChiselSim with FP32TestUtil {

  private val TIMEOUT = 200

  private def refSeq(a: Float, b: Float, sqrtOp: Boolean): Float = {
    if (sqrtOp) {
      math.sqrt(a.toDouble).toFloat
    } else {
      (a.toDouble / b.toDouble).toFloat
    }
  }

  private def enqueue(dut: FPSeqUnit, aBits: Int, bBits: Int, sqrtOp: Boolean, clue: String): Unit = {
    dut.io.in.bits.a.poke(intToUInt32(aBits))
    dut.io.in.bits.b.poke(intToUInt32(bBits))
    dut.io.in.bits.sqrtOp.poke(sqrtOp.B)
    dut.io.in.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.in.ready.peek().litToBoolean) {
      dut.clock.step()
      cycles += 1
      assert(cycles <= TIMEOUT, s"$clue enqueue: in.ready never asserted within $TIMEOUT cycles")
    }
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def dequeue(dut: FPSeqUnit, expected: Int, clue: String): Unit = {
    dut.io.out.ready.poke(true.B)
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step()
      cycles += 1
      assert(cycles <= TIMEOUT, s"$clue dequeue: timed out after $TIMEOUT cycles")
    }
    expectHex(dut.io.out.bits.data, expected, clue)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  "FPSeqUnit DIV" should "match IEEE-754 round-nearest-even on directed vectors" in {
    simulate(new FPSeqUnit(8, 24, FPOpMode.DIVSQRT)) { dut =>
      for ((label, aBits, bBits) <- vectors) {
        val expected = canonicalize(refSeq(intToFloat(aBits), intToFloat(bBits), sqrtOp = false))
        enqueue(dut, aBits, bBits, sqrtOp = false, clue = s"DIV($label)")
        dequeue(dut, expected, s"DIV($label)")
      }
    }
    info(s"DIV: ${vectors.length} vectors passed")
  }

  "FPSeqUnit SQRT" should "match IEEE-754 round-nearest-even on directed vectors" in {
    simulate(new FPSeqUnit(8, 24, FPOpMode.DIVSQRT)) { dut =>
      for ((label, aBits, bBits) <- vectors) {
        val expected = canonicalize(refSeq(intToFloat(aBits), intToFloat(bBits), sqrtOp = true))
        enqueue(dut, aBits, bBits, sqrtOp = true, clue = s"SQRT($label)")
        dequeue(dut, expected, s"SQRT($label)")
      }
    }
    info(s"SQRT: ${vectors.length} vectors passed")
  }
}