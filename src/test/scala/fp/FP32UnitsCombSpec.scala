// SPDX-License-Identifier: Apache-2.0
// See LICENSE file for details.
package fp

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

// Golden reference: Java Float arithmetic is IEEE-754 single-precision round-nearest-even,
// canonical NaN result becomes 0x7FC00000 which is what the NaN hardfloat/RISCV produce
trait FP32TestUtil { this: ChiselSim =>
  // bit <-> float helpers
  protected def intToFloat(bits: Int): Float = java.lang.Float.intBitsToFloat(bits)
  protected def intToUInt32(bits: Int): UInt = (bits.toLong & 0xFFFFFFFFL).U(32.W)
  // floatToIntBits to "canonicalize", which only affects NaN (every NaN -> 0x7FC00000); use for expected results
  protected def canonicalize(x: Float): Int = java.lang.Float.floatToIntBits(x)
  // floatToRawIntBits does straight conversion without canonicalizing; use for stimuli
  protected def floatToRawInt(x: Float): Int = java.lang.Float.floatToRawIntBits(x)
  // peek + assert so a failure prints "expected 0x.., got 0x.." in hex.
  protected def expectHex(actual: UInt, expected: Int, clue: String): Unit = {
    val got = actual.peek().litValue
    val exp = expected.toLong & 0xFFFFFFFFL
    assert(got == exp, f"$clue: expected 0x$exp%08x, got 0x$got%08x")
  }
  protected def expectHex(actual: BigInt, expected: Int, clue: String): Unit = {
    val got = actual
    val exp = expected.toLong & 0xFFFFFFFFL
    assert(got == exp, f"$clue: expected 0x$exp%08x, got 0x$got%08x")
  }
  protected def opName(mode: FPOpMode.Mode): String = mode.toString

  // f32 bit patterns      s/eemmmmmm
  protected val PosZero    = 0x00000000
  protected val NegZero    = 0x80000000
  protected val PosInf     = 0x7f800000 // 0__1111_1111__000_0000_0000_0000_0000_0000
  protected val NegInf     = 0xff800000 // 1__1111_1111__000_0000_0000_0000_0000_0000
  protected val QNaN       = 0x7fc00000 // quiet (indicated by mantissa MSB set); 0__1111_1111__100_0000_0000_0000_0000_0000
  protected val SNaN       = 0x7f800001 // signaling (indicated by mantissa MSB clear); 0__1111_1111__000_0000_0000_0000_0000_0001
  protected val MinSubnrm  = 0x00000001 // smallest positive subnormal
  protected val MaxSubnrm  = 0x007fffff // largest subnormal
  protected val MinNormal  = 0x00800000 // smallest positive normal
  protected val TwoToThe24 = floatToRawInt(16777216.0f) // 2^24 --> incs of 2.0 here so 2^24+1 tests testing round to even

  // test vectors
  protected val vectors: Seq[(String, Int, Int)] = Seq(
    ("+0 , +0",                     PosZero,    PosZero),
    ("+0 , -0",                     PosZero,    NegZero),
    ("-0 , -0",                     NegZero,    NegZero),
    ("+inf , 1.0",                  PosInf,     floatToRawInt(1.0f)),
    ("+inf , -inf",                 PosInf,     NegInf),
    ("1.0 , qNaN",                  floatToRawInt(1.0f),    QNaN),
    ("1.0 , sNaN",                  floatToRawInt(1.0f),    SNaN),
    ("maxSubnormal , minNormal",    MaxSubnrm,  MinNormal),
    ("minSubnormal , minSubnormal", MinSubnrm,  MinSubnrm),
    ("2^24 , 1.0 (tie-to-even)",    TwoToThe24, floatToRawInt(1.0f)),
    ("-1.0 , -2.0",                 floatToRawInt(-1.0f),   floatToRawInt(-2.0f)),
    ("3.14 , 2.71",                 floatToRawInt(3.14f),   floatToRawInt(2.71f)),
  )
}

class FP32UnitsCombSpec extends AnyFlatSpec with ChiselSim with FP32TestUtil {

  // goldem model is JVM
  private def ref(mode: FPOpMode.Mode, a: Float, b: Float): Float = mode match {
    case FPOpMode.ADD => a + b
    case FPOpMode.SUB => a - b
    case FPOpMode.MUL => a * b
  }

  private def runVectors(mode: FPOpMode.Mode): Unit = {
    simulate(new FPCombUnit(8, 24, mode)) { dut =>
      for ((label, aBits, bBits) <- vectors) {
        val expected = canonicalize(ref(mode, intToFloat(aBits), intToFloat(bBits)))
        dut.io.in_a.poke(intToUInt32(aBits))
        dut.io.in_b.poke(intToUInt32(bBits))
        expectHex(dut.io.out, expected, s"${opName(mode)}($label)")
      }
    }
    info(s"${opName(mode)}: ${vectors.length} vectors passed")
  }

  "FPCombUnit ADD" should "match IEEE-754 round-nearest-even on directed vectors" in {
    runVectors(FPOpMode.ADD)
  }

  "FPCombUnit SUB" should "match IEEE-754 round-nearest-even on directed vectors" in {
    runVectors(FPOpMode.SUB)
  }

  "FPCombUnit MUL" should "match IEEE-754 round-nearest-even on directed vectors" in {
    runVectors(FPOpMode.MUL)
  }
}