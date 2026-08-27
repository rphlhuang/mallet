// SPDX-License-Identifier: Apache-2.0
package mallet.contract

import chisel3._
import chisel3.util.RegEnable
import scala.collection.mutable
import mallet._
import axi.AxiLite32
import axi.AxiLiteResp.OKAY

// Formal-only observation hardware for the memory-map tier of properties
final class MapMonitors(axi: AxiLite32) {

  val awFire: Bool = axi.awvalid && axi.awready
  val wFire:  Bool = axi.wvalid  && axi.wready
  val arFire: Bool = axi.arvalid && axi.arready

  // In-flight write target: which address the pending B response belongs to
  val awAddr: UInt = RegEnable(axi.awaddr(19, 0), awFire)

  // In-flight read target: which address the pending R response belongs to
  val arAddr: UInt = RegEnable(axi.araddr(19, 0), arFire)

  // Write data travelling with that address
  // Note that AXI-Lite lets AW and W arrive in either order, 
  // so the Mux covers the case where the W handshake lands on the same cycle it is read.
  private val wDataHeld: UInt = RegEnable(axi.wdata, wFire)
  val wData: UInt = Mux(wFire, axi.wdata, wDataHeld)

  // The single cycle a write retires (B response asserted, no stall)
  val bRise: Bool = axi.bvalid && !RegNext(axi.bvalid, false.B)

  // The single cycle a read retires
  val rRise: Bool = axi.rvalid && !RegNext(axi.rvalid, false.B)

  // A write to "a" retired successfully this cycle
  def wroteOk(a: Long): Bool = bRise && (axi.bresp === OKAY.U) && (awAddr === a.U)

  // A read of "a" retired successfully this cycle
  def readOk(a: Long): Bool = rRise && (axi.rresp === OKAY.U) && (arAddr === a.U)

  private val writtenCache = mutable.Map.empty[Long, Bool]
  private val shadowCache  = mutable.Map.empty[Long, (Bool, UInt)]
  private val lastReadCache = mutable.Map.empty[Long, UInt]

  // has been written since reset: a sticky flag that "a" has seen at least one successful write
  def writtenSince(a: Long): Bool = writtenCache.getOrElseUpdate(a, {
    val w = RegInit(false.B)
    when(wroteOk(a)) { w := true.B }
    w.suggestName(f"mon_written_0x$a%x")
    w
  })

  // the last value successfully written to "a"
  def shadow(a: Long): (Bool, UInt) = shadowCache.getOrElseUpdate(a, {
    val v = RegInit(false.B)
    val d = Reg(UInt(32.W))
    when(wroteOk(a)) { v := true.B; d := wData }
    v.suggestName(f"mon_shadow_valid_0x$a%x")
    d.suggestName(f"mon_shadow_data_0x$a%x")
    (v, d)
  })

  // the data returned by the most recent successful read of "a"
  def lastRead(a: Long): UInt = lastReadCache.getOrElseUpdate(a, {
    val d = RegInit(0.U(32.W))
    when(readOk(a)) { d := axi.rdata }
    d.suggestName(f"mon_lastread_0x$a%x")
    d
  })
}
