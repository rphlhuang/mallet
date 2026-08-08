// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import chisel3.util.ShiftRegister
import chisel3.ltl._
import chisel3.ltl.Sequence._

// Realising past(n) in hardware
sealed trait PastBackend
case object LtlPast extends PastBackend

// Emit an explicit ShiftRegister with a known reset value so we can emit |-> + shr
case object ShiftRegPast extends PastBackend

// Renders AST to Chisel and to English.
object Render {

  // -------------------------------------------------------------------------
  // to Chisel
  // -------------------------------------------------------------------------

  /** Emit `AssertProperty` / `AssumeProperty` for one property.
    *
    * @param label   for later verdict attribution
    * @param prop    the property
    * @param warm    per-module memoized `warmedUp(n)` provider
    * @param kind    assert vs assume
    * @param backend how `past` is realised
    */
  def toChisel(
    label:   String,
    prop:    Prop,
    warm:    Int => Bool,
    kind:    PropKind = AssertK,
    backend: PastBackend = LtlPast
  ): Unit = {
    val p = Normalize(prop)
    val n = p.maxPast

    val body: Property = p match {

      case Implies(a, c) if n > 0 =>
        seq(warmSeq(warm, n), a, backend) |-> seqOf(c, backend)

      case Implies(a, c) =>
        seqOf(a, backend) |-> seqOf(c, backend)

      case Always(e) if n > 0 =>
        warmSeq(warm, n) |-> seqOf(e, backend)

      case Always(e) =>
        seqOf(e, backend)
    }

    kind match {
      case AssertK =>
        AssertProperty(body, label = Some(label))
      case AssumeK =>
        AssumeProperty(toBoolBody(p, warm), label = Some(label))
    }
  }

  // Property to Bool, used for assumptions, where clocked_assume form cannot carry an ltl.implication
  private def toBoolBody(p: Prop, warm: Int => Bool): Bool = {
    val n = p.maxPast
    p match {
      case Implies(a, c) =>
        val ante = if (n > 0) warm(n) && toBool(a, ShiftRegPast) else toBool(a, ShiftRegPast)
        !ante || toBool(c, ShiftRegPast)
      case Always(e) =>
        if (n > 0) !warm(n) || toBool(e, ShiftRegPast) else toBool(e, ShiftRegPast)
    }
  }

  // Metadata cover label for a property
  def coverLabelFor(parentLabel: String, prop: Prop): Option[String] =
    Normalize(prop) match {
      case _: Implies => Some("COVER_" + parentLabel)
      case _: Always  => None
    }

  // Emit the reachability-cover hardware for an implication property
  def emitCover(coverLabel: String, prop: Prop, warm: Int => Bool): Unit =
    Normalize(prop) match {
      case Implies(ante, _) =>
        val n    = ante.maxPast
        val mask = if (n > 0) warm(n) else true.B
        val antBool = mask && toBool(ante, ShiftRegPast)
        AssertProperty(!antBool, label = Some(coverLabel))
      case Always(_) => ()
    }

  private def warmSeq(warm: Int => Bool, n: Int): Sequence = Sequence.BoolSequence(warm(n))

  private def seq(w: Sequence, a: Expr, backend: PastBackend): Sequence =
    w.and(seqOf(a, backend))

  // Render an expression as a Sequence
  // Note that maxPast == 0 means that any subtree that does not reach back in time
  // becomes plain Chisel Bool logic, which is trivially lowerable
  // Only genuinely temporal subtrees pay for ltl.and / ltl.or
  private def seqOf(e: Expr, backend: PastBackend): Sequence =
    if (e.maxPast == 0 || backend == ShiftRegPast) {
      Sequence.BoolSequence(toBool(e, backend))
    } else {
      e match {
        case Past(a, n) => Sequence.BoolSequence(toBool(a, backend)).past(n)
        case And(a, b)  => seqOf(a, backend).and(seqOf(b, backend))
        case Or(a, b)   => seqOf(a, backend).or(seqOf(b, backend))
        case Not(_) =>
          throw new IllegalStateException(
            s"Not above Past survived normalization in: ${e.canon}"
          )
        case other =>
          throw new IllegalStateException(s"unreachable in seqOf: ${other.canon}")
      }
    }

  // Render an expression as a Chisel Bool
  // Only legal when the expression has maxPast == 0
  private def toBool(e: Expr, backend: PastBackend): Bool = e match {
    case B(d, _)       => d
    case TrueE         => true.B
    case FalseE        => false.B
    case Not(a)        => !toBool(a, backend)
    case And(a, b)     => toBool(a, backend) && toBool(b, backend)
    case Or(a, b)      => toBool(a, backend) || toBool(b, backend)
    case Cmp(op, l, r) => op(l.toData, r.toData)
    case Past(a, n) =>
      backend match {
        case ShiftRegPast => ShiftRegister(toBool(a, backend), n, false.B, true.B)
        case LtlPast =>
          throw new IllegalStateException(
            s"Past reached toBool under the LtlPast backend: ${e.canon}"
          )
      }
  }

  // -------------------------------------------------------------------------
  // English
  // -------------------------------------------------------------------------

  // Render a property as an English sentence
  def toNL(p: Prop): String = {
    val core = p match {
      case Implies(a, c) => s"Whenever ${nl(a)}, ${nl(c)}."
      case Always(e)     => s"At all times, ${nl(e)}."
    }
    val n = p.maxPast
    // Warmup mask note: it makes the property vacuously true for the first N cycles
    if (n > 0) s"$core (Not checked for the first $n cycle(s) after reset.)" else core
  }

  private def name(d: Bool, alias: String): String =
    if (alias.nonEmpty) alias else Term.safeName(d)

  private def nl(e: Expr): String = e match {
    case B(d, alias)          => s"${name(d, alias)} is high"
    case TrueE                => "true"
    case FalseE               => "false"
    case Not(B(d, a))         => s"${name(d, a)} is low"
    case Not(Past(B(d, a), n)) => s"${name(d, a)} was low ${cycleRef(n)}"
    case Not(x)               => s"it is not the case that ${nl(x)}"
    case And(a, b)            => s"${nl(a)} and ${nl(b)}"
    case Or(a, b)             => s"${nl(a)} or ${nl(b)}"
    case Cmp(op, l, r)        => s"${l.nl} ${op.word} ${r.nl}"
    case Past(a, n)           => s"${nlPast(a)} ${cycleRef(n)}"
  }

  private def cycleRef(n: Int): String =
    if (n == 1) "on the previous cycle" else s"$n cycles ago"

  private def nlPast(e: Expr): String = e match {
    case B(d, a)       => s"${name(d, a)} was high"
    case Not(B(d, a))  => s"${name(d, a)} was low"
    case And(a, b)     => s"${nlPast(a)} and ${nlPast(b)}"
    case Or(a, b)      => s"${nlPast(a)} or ${nlPast(b)}"
    case Cmp(op, l, r) => s"${l.nl} ${op.word} ${r.nl}"
    case TrueE         => "true"
    case FalseE        => "false"
    case other         => s"${nl(other)}, which held"
  }
}
