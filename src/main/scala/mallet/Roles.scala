// SPDX-License-Identifier: Apache-2.0
package mallet

// Memory-map access modes and roles
// UVM RAL-ish access policy taxonomy: https://verificationacademy.com/verification-methodology-reference/uvm/docs_1.1a/html/files/reg/uvm_reg_field-svh.html
// SystemRDL equivalents: https://systemrdl-compiler.readthedocs.io/en/stable/api/types.html

// access policy template
sealed abstract class Access(val label: String) {
  /** Software may read without the read being refused by policy. */
  def readable: Boolean
  /** Software may write without the write being refused by policy. */
  def writable: Boolean
  /** A read has a side effect on the value, so readback/idempotence do not hold. */
  def destructiveRead: Boolean = false
  /** A read returns exactly the last value software wrote. */
  def readbackGuaranteed: Boolean = false
}

// read-only (writes must be refused)
case object RO extends Access("RO") {
  def readable = true
  def writable = false
}

// write-only (reads must be refused)
case object WO extends Access("WO") {
  def readable = false
  def writable = true
}

// readable and writable
case object RW extends Access("RW") {
  def readable = true
  def writable = true
}

// software-owned storage, where a read returns exactly the last value written
// good for config/on-the-fly param sets, where hardware constantly monitors
case object Storage extends Access("Storage") {
  def readable = true; def writable = true
  override def readbackGuaranteed = true
}

// read-only, and read clears (unlike RO, can error if nothing to consume)
case object RC extends Access("RC") {
  def readable = true
  def writable = false
  override def destructiveRead = true
}

// read for status, write 1 to clear; convention for interrupt-status and clearing the fault
// clearing is unprovable, so this is just more lax than WO
case object W1C extends Access("W1C") {
  def readable = true
  def writable = true
}

// roles are a (access mode, relation) pair
// 
// a relation takes another addr in the map:
//  Status  = RO + setBy    <commitAddr>
//  Result  = RC + gatedBy  <statusAddr>
//  Commit  = WO + requiring(<operandAddrs>)
sealed abstract class Role(val access: Access, val label: String)
case object Status extends Role(RO, "Status")
case object Result extends Role(RC, "Result")
case object Commit extends Role(WO, "Commit")
