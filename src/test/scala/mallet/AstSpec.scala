// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AstSpec extends AnyFlatSpec with Matchers {

  private def withSignals[T](body: (Bool, Bool, UInt) => T): T = {
    var out: Option[T] = None
    ChiselStage.emitCHIRRTL(new RawModule {
      val a = IO(Input(Bool()))
      val b = IO(Input(Bool()))
      val w = IO(Input(UInt(8.W)))
      out = Some(body(a, b, w))
    })
    out.get
  }

  behavior of "maxPast"

  it should "be additive under nested past" in withSignals { (a, _, _) =>
    Past(Past(B(a), 1), 2).maxPast shouldBe 3
    B(a).past(1).past(2).maxPast shouldBe 3
  }

  it should "take the max across siblings" in withSignals { (a, b, _) =>
    And(Past(B(a), 1), Past(B(b), 4)).maxPast shouldBe 4
    Or(Past(B(a), 5), B(b)).maxPast shouldBe 5
  }

  it should "take the max over BOTH sides of an implication" in withSignals { (a, b, _) =>
    Implies(B(a), Past(B(b), 3)).maxPast shouldBe 3
    Implies(Past(B(a), 2), Past(B(b), 3)).maxPast shouldBe 3
    Implies(Past(B(a), 7), B(b)).maxPast shouldBe 7
  }

  it should "be zero for purely combinational properties" in withSignals { (a, b, _) =>
    Implies(B(a), B(b)).maxPast shouldBe 0
    Always(And(B(a), Not(B(b)))).maxPast shouldBe 0
  }

  it should "treat past(0) as a no-op" in withSignals { (a, _, _) =>
    B(a).past(0) shouldBe B(a)
    B(a).past(0).maxPast shouldBe 0
  }

  behavior of "|=> (non-overlapping implication)"

  it should "desugar a |=> b to a.past(1) |-> b" in withSignals { (a, b, _) =>
    (B(a) |=> B(b)) shouldBe Implies(Past(B(a), 1), B(b))
  }

  it should "force a warm-up depth of at least 1" in withSignals { (a, b, _) =>
    (B(a) |=> B(b)).maxPast shouldBe 1
    // depth propagates from a past in the antecedent too
    (B(a).past(2) |=> B(b)).maxPast shouldBe 3
  }

  behavior of "Normalize"

  it should "push Not below Past so it never sits above a sequence" in withSignals { (a, _, _) =>
    val n = Normalize.expr(Not(Past(B(a), 1)))
    n shouldBe Past(Not(B(a)), 1)
  }

  it should "distribute Past over And and Or" in withSignals { (a, b, _) =>
    Normalize.expr(Past(And(B(a), B(b)), 2)) shouldBe And(Past(B(a), 2), Past(B(b), 2))
    Normalize.expr(Past(Or(B(a), B(b)), 1)) shouldBe Or(Past(B(a), 1), Past(B(b), 1))
  }

  it should "merge nested Past additively" in withSignals { (a, _, _) =>
    Normalize.expr(Past(Past(B(a), 1), 2)) shouldBe Past(B(a), 3)
  }

  it should "apply De Morgan and absorb negation into comparisons" in withSignals { (a, b, w) =>
    Normalize.expr(Not(And(B(a), B(b)))) shouldBe Or(Not(B(a)), Not(B(b)))
    Normalize.expr(Not(Or(B(a), B(b)))) shouldBe And(Not(B(a)), Not(B(b)))
    Normalize.expr(Not(Cmp(CmpOp.Eq, Sig(w), Lit(3)))) shouldBe Cmp(CmpOp.Neq, Sig(w), Lit(3))
    Normalize.expr(Not(Not(B(a)))) shouldBe B(a)
  }

  it should "preserve maxPast" in withSignals { (a, b, _) =>
    val e = Not(Past(And(B(a), Past(B(b), 2)), 1))
    Normalize.expr(e).maxPast shouldBe e.maxPast
  }

  behavior of "toNL"

  it should "disclose the warm-up window whenever one is applied" in withSignals { (a, b, _) =>
    Render.toNL(Implies(Past(B(a, "x"), 2), B(b, "y"))) should include("first 2 cycle")
    Render.toNL(Implies(B(a, "x"), B(b, "y"))) should not include "after reset"
  }

  behavior of "labelFor"

  it should "change when the property changes but not when it is reordered" in withSignals { (a, b, _) =>
    val p1 = Implies(B(a, "x"), B(b, "y"))
    val p2 = Implies(B(a, "x"), Not(B(b, "y")))
    val hash: String => String = _.split("_").last

    hash(MalletRegistry.labelFor(1, "p", p1)) should not be hash(MalletRegistry.labelFor(1, "p", p2))
    // same property at a different index keeps its hash
    hash(MalletRegistry.labelFor(1, "p", p1)) shouldBe hash(MalletRegistry.labelFor(7, "p", p1))
  }
}
