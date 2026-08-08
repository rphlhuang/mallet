// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import scala.annotation.implicitNotFound

// Memory-map access modes: mandatory per address
sealed trait Access
case object RO  extends Access
case object WO  extends Access
case object RW  extends Access
case object W1C extends Access

// Role: constrains address with meaning and an Access.
sealed trait Role
case object Operand extends Role // implies WO
case object Status  extends Role // implies RO
case object Result  extends Role // implies RO
case object Commit  extends Role // implies W

// Type verification for Operand/Result signals, force a compile error
@implicitNotFound("Operand/Result backing must be a multi-bit UInt, not a Bool")
sealed trait UIntBacking[T]
object UIntBacking {
  implicit def ok[T <: UInt]: UIntBacking[T] = new UIntBacking[T] {}
  implicit def ambiguousBool1: UIntBacking[Bool] = new UIntBacking[Bool] {}
  implicit def ambiguousBool2: UIntBacking[Bool] = new UIntBacking[Bool] {}
}