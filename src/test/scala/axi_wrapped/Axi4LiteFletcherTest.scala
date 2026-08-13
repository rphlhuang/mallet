// SPDX-License-Identifier: Apache-2.0
package axi_wrapped

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class Axi4LiteFletcherTest extends AnyFlatSpec with ChiselSim {
  val CHECKSUM_W = 16
  val INPUT_W    = 32
  val LATENCY    = 1
  val TIMEOUT    = 50
  
  def golden(x: Long): BigInt = {
    val nBytes = (INPUT_W + 7) / 8
    var a = 0; var b = 0
    for (i <- 0 until nBytes) {
      val d = ((x >>> (8 * i)) & 0xff).toInt
      a = (a + d) % 255
      b = (b + a) % 255
    }
    BigInt((b << 8) | a)
  }

  def runOne(dut: FletcherUnit, data: Long): Unit = {
    dut.io.in.bits.poke(data.U)
    dut.io.in.valid.poke(true.B)
    var c = 0
    while (!dut.io.in.ready.peek().litToBoolean) {
      dut.clock.step(); c += 1; assert(c <= TIMEOUT, s"in handshake timed out after $TIMEOUT cycles")
    }
    dut.clock.step()
    dut.io.in.valid.poke(false.B)

    dut.io.out.ready.poke(true.B)
    c = 0
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step(); c += 1; assert(c <= TIMEOUT, s"out handshake timed out after $TIMEOUT cycles")
    }
    dut.io.out.bits.expect(golden(data).U, s"Fletcher-16(0x${data.toHexString}) mismatch")
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  "Axi4LiteFletcher's FletcherUnit" should "match a Fletcher-16 golden for two stimuli" in {
    simulate(new FletcherUnit(CHECKSUM_W, INPUT_W, LATENCY)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      runOne(dut, 0x00000000L) // stimulus 1: all-zero -> checksum 0
      runOne(dut, 0x12345678L) // stimulus 2: mixed bytes
    }
  }
}
