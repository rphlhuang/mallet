// SPDX-License-Identifier: Apache-2.0
package mallet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BlackBoxRoleSpec extends AnyFlatSpec with Matchers {

  private val builders = Seq(
    "mallet.MalletSpec$AddressOps",
    "mallet.MalletSpec$AccessBuilder",
    "mallet.MalletSpec$StatusBuilder",
    "mallet.MalletSpec$ResultBuilder",
    "mallet.MalletSpec$CommitBuilder"
  )

  behavior of "the memory-map annotation surface"

  it should "never accept a chisel3.Data anywhere in the role/access builders" in {
    val data = classOf[chisel3.Data]

    def paramsOf(cn: String): Seq[(String, Class[_])] = {
      val cls = Class.forName(cn)
      val fromMethods = cls.getDeclaredMethods.toSeq.flatMap { m =>
        m.getParameterTypes.toSeq.map(p => (s"$cn.${m.getName}", p))
      }
      val fromCtors = cls.getDeclaredConstructors.toSeq.flatMap { c =>
        c.getParameterTypes.toSeq.map(p => (s"$cn.<init>", p))
      }
      fromMethods ++ fromCtors
    }

    val offenders = builders.flatMap(paramsOf).collect {
      case (where, p) if data.isAssignableFrom(p) => s"$where takes ${p.getName}"
    }

    withClue(
      "the memory-map tier reached into the DUT; a property that needs an internal " +
      "signal belongs in the manual tier (property/assume), not in a role:\n  " +
        offenders.mkString("\n  ") + "\n"
    ) {
      offenders shouldBe empty
    }
  }

  it should "expose exactly the frozen relation vocabulary" in {
    def methodsOf(cn: String) =
      Class.forName(cn).getDeclaredMethods.map(_.getName).filterNot(_.contains("$")).toSet

    methodsOf("mallet.MalletSpec$AccessBuilder") shouldBe Set("gatedBy")
    methodsOf("mallet.MalletSpec$StatusBuilder") shouldBe Set("setBy")
    methodsOf("mallet.MalletSpec$ResultBuilder") shouldBe Set("gatedBy")
    methodsOf("mallet.MalletSpec$CommitBuilder") shouldBe Set("requiring")
  }

  it should "bind every role to the access mode it refines" in {
    Status.access shouldBe RO
    Result.access shouldBe RC
    Commit.access shouldBe WO

    RC.destructiveRead shouldBe true
    RO.destructiveRead shouldBe False

    Storage.readbackGuaranteed shouldBe true
    RW.readbackGuaranteed      shouldBe false
  }
}
