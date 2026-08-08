// SPDX-License-Identifier: Apache-2.0
package mallet.contract

import chisel3._
import mallet._

// Helpers that allocate formal-only hardware.
object Monitors {

  private type Mk = (String, Prop, String) => NamedProp

  /** Payload must not change while its channel is stalled (VALID && !READY).
    *
    * Expressing this directly would need past() on a bus, which the fragment does
    * not have (past is Bool-only). Instead we capture the previous payload and a
    * one-cycle-delayed stall flag, then assert equality when the previous cycle
    * was stalled, a combinational property over two monitor registers.
    */
  def stableWhileStalled(
    name: String, valid: Bool, ready: Bool, payload: UInt, chan: String, mk: Mk
  ): NamedProp = {
    val stalled    = valid && !ready
    val prev       = RegNext(payload)             // payload one cycle ago
    val wasStalled = RegNext(stalled, false.B)    // was the channel stalled last cycle
    mk(
      name,
      B(wasStalled, s"${chan}Stalled") ==>
        Cmp(CmpOp.Eq, Sig(payload, chan), Sig(prev, s"${chan}Prev")),
      s"AXI4-Lite: $chan must be held stable while its channel is stalled (VALID && !READY)"
    )
  }

  /** A response may only be asserted while a matching request is outstanding.
    *
    * A single sticky flag: set when the request handshake fires, cleared when the
    * response handshake fires. respValid |-> outstanding then rules out a
    * response the master never asked for.
    */
  def responseSolicited(
    name: String, reqFire: Bool, respValid: Bool, respFire: Bool, chan: String, note: String
  ): NamedProp = {
    val outstanding = RegInit(false.B)
    when(reqFire)  { outstanding := true.B }
    when(respFire) { outstanding := false.B }
    NamedProp.assert(
      name,
      B(respValid, s"${chan}valid") ==> B(outstanding, s"${chan}Outstanding"),
      note
    )
  }

  /** Write variant: a B response needs BOTH the address and data handshakes to
    * have occurred since the last response (AXI-Lite lets AW and W arrive in
    * either order, so they are tracked separately).
    */
  def writeResponseSolicited(
    name: String, awFire: Bool, wFire: Bool, bValid: Bool, bFire: Bool, note: String
  ): NamedProp = {
    val awSeen = RegInit(false.B)
    val wSeen  = RegInit(false.B)
    when(awFire) { awSeen := true.B }
    when(wFire)  { wSeen  := true.B }
    when(bFire)  { awSeen := false.B; wSeen := false.B }
    NamedProp.assert(
      name,
      B(bValid, "bvalid") ==> (B(awSeen, "awAccepted") && B(wSeen, "wAccepted")),
      note
    )
  }
}
