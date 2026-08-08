// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._

// ---------------------------------------------------------------------------
// Word-level operands
// ---------------------------------------------------------------------------

sealed trait Term {
  def toData: UInt // Chisel value
  def nl: String // English rendering
  def canon: String // key for hashing
}

// Signal
final case class Sig(d: UInt, alias: String = "") extends Term {
  def toData: UInt = d
  def nl: String = if (alias.nonEmpty) alias else Term.safeName(d)
  def canon: String = s"sig(${if (alias.nonEmpty) alias else "?"})"
}

// Bit-slice of a signal
final case class Slice(d: UInt, hi: Int, lo: Int, alias: String = "") extends Term {
  require(hi >= lo && lo >= 0, s"bad slice [$hi:$lo]")
  def toData: UInt = d(hi, lo)
  def nl: String = s"${if (alias.nonEmpty) alias else Term.safeName(d)}[$hi:$lo]"
  def canon: String = s"slice(${if (alias.nonEmpty) alias else "?"},$hi,$lo)"
}

// Literal/constant
final case class Lit(v: BigInt, alias: String = "") extends Term {
  def toData: UInt = v.U
  def nl: String = if (alias.nonEmpty) alias else s"0x${v.toString(16)}"
  def canon: String = s"lit($v)"
}

object Term {
  def safeName(d: Data): String =
    try d.instanceName
    catch { case _: Throwable => "<signal>" }
}

// ---------------------------------------------------------------------------
// Comparison operators
// ---------------------------------------------------------------------------

sealed abstract class CmpOp(val sym: String, val word: String) {
  def apply(l: UInt, r: UInt): Bool
  def negate: CmpOp
}

object CmpOp {
  case object Eq extends CmpOp("===", "equals") {
    def apply(l: UInt, r: UInt): Bool = l === r
    def negate: CmpOp = Neq
  }
  case object Neq extends CmpOp("=/=", "differs from") {
    def apply(l: UInt, r: UInt): Bool = l =/= r
    def negate: CmpOp = Eq
  }
  case object Lt extends CmpOp("<", "is less than") {
    def apply(l: UInt, r: UInt): Bool = l < r
    def negate: CmpOp = Ge
  }
  case object Le extends CmpOp("<=", "is at most") {
    def apply(l: UInt, r: UInt): Bool = l <= r
    def negate: CmpOp = Gt
  }
  case object Gt extends CmpOp(">", "is greater than") {
    def apply(l: UInt, r: UInt): Bool = l > r
    def negate: CmpOp = Le
  }
  case object Ge extends CmpOp(">=", "is at least") {
    def apply(l: UInt, r: UInt): Bool = l >= r
    def negate: CmpOp = Lt
  }
}

// ---------------------------------------------------------------------------
// Bool-valued expressions
// ---------------------------------------------------------------------------

sealed trait Expr {
  def &&(o: Expr): Expr = And(this, o)
  def ||(o: Expr): Expr = Or(this, o)
  def unary_! : Expr = Not(this)

  // Sample this expression n cycles ago
  def past(n: Int): Expr = {
    require(n >= 0, s"past depth must be non-negative, got $n")
    if (n == 0) this else Past(this, n)
  }

  // Overlappping implication: a |-> b holds iff, in the same cycle, a implies b
  def ==>(c: Expr): Prop = Implies(this, c)

  // Non-overlappping implication: rewritten to prevent current CIRCT lowering problems
  def |=>(c: Expr): Prop = Implies(this.past(1), c)

  /** How far back in time this expression reaches.
    *
    * Two rules:
    *   - ADDITIVE under nesting: `Past(Past(a,1),2)` samples 3 cycles back,
    *     because CIRCT lowers it to a shiftreg feeding a shiftreg.
    *   - MAX across siblings.
    */
  final def maxPast: Int = this match {
    case Past(a, n) => n + a.maxPast
    case Not(a)     => a.maxPast
    case And(a, b)  => math.max(a.maxPast, b.maxPast)
    case Or(a, b)   => math.max(a.maxPast, b.maxPast)
    case _          => 0
  }

  // Structural key for hashing
  final def canon: String = this match {
    case B(_, alias)   => s"b(${if (alias.nonEmpty) alias else "?"})"
    case TrueE         => "true"
    case FalseE        => "false"
    case Not(a)        => s"not(${a.canon})"
    case And(a, b)     => s"and(${a.canon},${b.canon})"
    case Or(a, b)      => s"or(${a.canon},${b.canon})"
    case Cmp(op, l, r) => s"cmp(${op.sym},${l.canon},${r.canon})"
    case Past(a, n)    => s"past(${a.canon},$n)"
  }
}

final case class B(d: Bool, alias: String = "") extends Expr
case object TrueE extends Expr
case object FalseE extends Expr
final case class Not(a: Expr) extends Expr
final case class And(a: Expr, b: Expr) extends Expr
final case class Or(a: Expr, b: Expr) extends Expr
final case class Cmp(op: CmpOp, l: Term, r: Term) extends Expr
final case class Past(a: Expr, n: Int) extends Expr

// ---------------------------------------------------------------------------
// Top-level properties
// ---------------------------------------------------------------------------

sealed trait Prop {

  // The warmup depth this property requires; for an implication this is the max over both sides.
  def maxPast: Int = this match {
    case Implies(a, c) => math.max(a.maxPast, c.maxPast)
    case Always(e)     => e.maxPast
  }

  def canon: String = this match {
    case Implies(a, c) => s"implies(${a.canon},${c.canon})"
    case Always(e)     => s"always(${e.canon})"
  }
}

// Overlapping implication
final case class Implies(ante: Expr, cons: Expr) extends Prop

// Safety property: e in every cycle
final case class Always(e: Expr) extends Prop

// Assert vs Assume semantics
sealed trait PropKind
case object AssertK extends PropKind
case object AssumeK extends PropKind

// Frontend of Ast.scala: a property plus its stable identity, description, and kind
final case class NamedProp(name: String, prop: Prop, note: String = "", kind: PropKind = AssertK)

object NamedProp {
  def assert(name: String, prop: Prop, note: String = ""): NamedProp =
    NamedProp(name, prop, note, AssertK)
  def assume(name: String, prop: Prop, note: String = ""): NamedProp =
    NamedProp(name, prop, note, AssumeK)
}

// ---------------------------------------------------------------------------
// Normalization
// ---------------------------------------------------------------------------

object Normalize {

  /** [Claude] Rewrite into the form the renderer can emit.
    *
    * Two passes:
    *   1. negation-normal form - push `Not` down to atoms (DeMorgan's, and absorb into comparison operators).
    *   2. push `Past` down to atoms, merging nested `Past`.
    *
    * Both are semantics-preserving because `past` is a pure sampling function,
    * not a temporal quantifier: sampling a conjunction equals conjoining the
    * samples.
    *
    * The point of the second pass is that afterwards, any subtree with
    * `maxPast == 0` renders as plain Chisel `Bool` logic -- which is trivially
    * lowerable and folds well. Only subtrees that actually reach back in time
    * need `Sequence`-level connectives.
    *
    * The point of the FIRST pass is subtler and is a hard API constraint:
    * `Property.not` returns a `Property`, not a `Sequence`, so a `Not` sitting
    * above a `Past` could not be used as an implication antecedent at all.
    * Pushing `Not` to the leaves guarantees the renderer never meets that case.
    */
  def apply(p: Prop): Prop = p match {
    case Implies(a, c) => Implies(expr(a), expr(c))
    case Always(e)     => Always(expr(e))
  }

  def expr(e: Expr): Expr = pushPast(nnf(e, negated = false))

  /** Pass 1: negation-normal form. */
  private def nnf(e: Expr, negated: Boolean): Expr = e match {
    case Not(a) => nnf(a, !negated)

    case And(a, b) =>
      // De Morgan under negation
      if (negated) Or(nnf(a, true), nnf(b, true)) else And(nnf(a, false), nnf(b, false))

    case Or(a, b) =>
      if (negated) And(nnf(a, true), nnf(b, true)) else Or(nnf(a, false), nnf(b, false))

    case Cmp(op, l, r) =>
      // absorb the negation into the operator rather than leaving a Not node
      if (negated) Cmp(op.negate, l, r) else Cmp(op, l, r)

    case Past(a, n) =>
      // `past` commutes with negation: !past(x,n) == past(!x,n), given the
      // warm-up guard makes the pre-warm cycles vacuous either way.
      Past(nnf(a, negated), n)

    case TrueE  => if (negated) FalseE else TrueE
    case FalseE => if (negated) TrueE else FalseE

    // atoms: a Not directly above a Bool signal is kept, and is renderable
    // because it is below any Past after pass 2.
    case b: B => if (negated) Not(b) else b
  }

  /** Pass 2: push `Past` down to atoms and merge nesting. */
  private def pushPast(e: Expr): Expr = e match {
    case Past(a, n) =>
      pushPast(a) match {
        case And(x, y)  => And(pushPast(Past(x, n)), pushPast(Past(y, n)))
        case Or(x, y)   => Or(pushPast(Past(x, n)), pushPast(Past(y, n)))
        case Past(x, m) => pushPast(Past(x, m + n)) // additive, per maxPast
        case TrueE      => TrueE
        case FalseE     => FalseE
        case atom       => Past(atom, n) // B, Not(B), Cmp -- stop here
      }
    case And(a, b) => And(pushPast(a), pushPast(b))
    case Or(a, b)  => Or(pushPast(a), pushPast(b))
    case Not(a)    => Not(pushPast(a)) // only ever above an atom, post-nnf
    case other     => other
  }
}
