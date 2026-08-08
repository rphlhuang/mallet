// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import chisel3.util.RegEnable
import scala.collection.mutable
import axi.{AxiLite32, HasAxiLite32IO}
import axi.AxiLiteResp.OKAY
import mallet.contract.{AxiLite32Contract, ContractSet}

/** The infix syntax as an interface.
  * Subclass of the DUT (the only way I could think of doing it cleanly).
  * MalletSpec adds no new lowering, just convenience.
  *
  * done() is a workaround due to how Chisel rendering works;
  * registration emits hardware but hardware may only be added while the module is still open,
  * so done() flushes at the end of the spec body.
  */
trait MalletSpec extends MalletProperties { this: chisel3.Module with HasAxiLite32IO =>

  // Source-ordered generators, run at done()
  private val items   = mutable.ArrayBuffer.empty[() => Seq[NamedProp]]
  private val roleReg = mutable.ArrayBuffer.empty[RoleSpec]
  private val ledger  = mutable.ArrayBuffer.empty[String]

  private[mallet] def enqueue(np: NamedProp): Unit = { items += (() => Seq(np)); () }

  // Raw annotation
  protected def property(name: String)(e: Prop): Unit =
    enqueue(NamedProp.assert(name, e))
  protected def property(name: String, desc: String)(e: Prop): Unit =
    enqueue(NamedProp.assert(name, e, desc))

  // Let a bare Chisel Bool stand in for an Expr inside a property block.
  import scala.language.implicitConversions
  protected implicit def boolToExpr(b: Bool): B = B(b)

  // Protocol contract
  protected val AxiLite32Slave: ContractSet[AxiLite32] = AxiLite32Contract
  protected implicit class ConformsOps(bundle: AxiLite32) {
    def conformsTo(set: ContractSet[AxiLite32]): Unit = {
      val ps = set.properties(bundle) // allocates monitor hardware asap
      items += (() => ps)
    }
  }

  // ---- Memory-map roles + access ----
  private sealed trait RoleSpec
  private case class OperandSpec(addr: Long, sig: UInt) extends RoleSpec
  private case class StatusSpec (addr: Long, sig: Bool) extends RoleSpec
  private case class ResultSpec (addr: Long, sig: UInt, valid: Bool) extends RoleSpec
  private case class CommitSpec (addr: Long, pending: Bool, requires: Seq[UInt], accepted: Bool) extends RoleSpec

  private def stage(spec: RoleSpec, note: String): Unit = {
    roleReg += spec
    ledger  += note
    items   += (() => genRole(spec))
    ()
  }

  protected implicit class AddressOps(addr: Long) {
    def is(a: Access): Unit = {
      ledger += f"0x$addr%02x  $a%-3s"
      items  += (() => genAccess(addr, a))
      ()
    }
    def is(r: Operand.type): OperandBuilder = new OperandBuilder(addr)
    def is(r: Status.type):  StatusBuilder  = new StatusBuilder(addr)
    def is(r: Result.type):  ResultBuilder  = new ResultBuilder(addr)
    def is(r: Commit.type):  CommitBuilder   = new CommitBuilder(addr)
  }

  protected class OperandBuilder(addr: Long) {
    def at[T <: UInt](sig: T)(implicit ev: UIntBacking[T]): Unit =
      stage(OperandSpec(addr, sig), f"0x$addr%02x  Operand (WO)")
  }
  protected class StatusBuilder(addr: Long) {
    def at(sig: Bool): Unit = stage(StatusSpec(addr, sig), f"0x$addr%02x  Status  (RO)")
  }
  protected class ResultBuilder(addr: Long) {
    def at[T <: UInt](sig: T)(implicit ev: UIntBacking[T]): ResultNeedsValidWhen = new ResultNeedsValidWhen(addr, sig)
  }
  protected class ResultNeedsValidWhen(addr: Long, sig: UInt) {
    def validWhen(v: Bool): Unit = stage(ResultSpec(addr, sig, v), f"0x$addr%02x  Result  (RO)")
  }
  protected class CommitBuilder(addr: Long) {
    def at(sig: Bool): CommitNeedsRequiring = new CommitNeedsRequiring(addr, sig)
  }
  protected class CommitNeedsRequiring(addr: Long, pending: Bool) {
    def requiring(sigs: UInt*): CommitNeedsAcceptedOn = new CommitNeedsAcceptedOn(addr, pending, sigs)
  }
  protected class CommitNeedsAcceptedOn(addr: Long, pending: Bool, requires: Seq[UInt]) {
    def acceptedOn(k: Bool): Unit = stage(CommitSpec(addr, pending, requires, k), f"0x$addr%02x  Commit  (W)")
  }

  // Role -> properties
  private def genRole(spec: RoleSpec): Seq[NamedProp] = spec match {
    case _: OperandSpec                => Seq.empty     // latch deferred; WO access has no props
    case StatusSpec(addr, sig)         => Seq(statusRead(addr, sig))
    case ResultSpec(addr, sig, valid)  => resultRole(addr, sig, valid)
    case CommitSpec(addr, pend, req, acc) => commitRole(addr, pend, req, acc)
  }

  // operand signal to address
  private def operandAddrOf(sig: UInt): Long =
    roleReg.collectFirst { case OperandSpec(a, s) if s.eq(sig) => a }.getOrElse(
      throw new IllegalArgumentException("requiring(...) references a signal not registered as `is Operand`"))

  // "a read of A returns sig"
  private def statusRead(addr: Long, sig: Bool): NamedProp = {
    val axi  = S.AXI
    val prev = RegNext(sig)
    val hex  = f"0x$addr%x"
    val readHit = B(axi.arvalid && axi.arready, "arFire") &&
                  Cmp(CmpOp.Eq, Slice(axi.araddr, 19, 0, "araddr"), Lit(BigInt(addr), hex))
    NamedProp.assert(
      s"gen_status_read_$hex",
      readHit |=> Cmp(CmpOp.Eq, Sig(axi.rdata, "rdata"), Sig(prev, "status_prev")),
      s"a read of $hex returns its status bit"
    )
  }

  // "a read of A while valid returns sig" + "reading A clears valid"
  private def resultRole(addr: Long, sig: UInt, valid: Bool): Seq[NamedProp] = {
    val axi  = S.AXI
    val hex  = f"0x$addr%x"
    val prev = RegNext(sig)
    val readHit = B(axi.arvalid && axi.arready, "arFire") &&
                  Cmp(CmpOp.Eq, Slice(axi.araddr, 19, 0, "araddr"), Lit(BigInt(addr), hex))
    Seq(
      NamedProp.assert(
        s"gen_result_read_$hex",
        (readHit && B(valid, "valid")) |=> Cmp(CmpOp.Eq, Sig(axi.rdata, "rdata"), Sig(prev, "result_prev")),
        s"a read of $hex while valid returns its data"
      ),
      NamedProp.assert(
        s"gen_result_read_clears_$hex",
        readHit |=> Not(B(valid, "valid")),
        s"reading $hex clears its valid flag"
      )
    )
  }

  // ("pending clears once accepted", asserted) + for upstream, ("commit fires only with every operand written since reset", assumed)
  private def commitRole(addr: Long, pending: Bool, requires: Seq[UInt], accepted: Bool): Seq[NamedProp] = {
    val axi = S.AXI
    val hex = f"0x$addr%x"

    val retires = NamedProp.assert(
      s"gen_commit_retires_$hex",
      (B(pending, "pending") && B(accepted, "accepted")) |=> Not(B(pending, "pending")),
      s"a pending commit at $hex clears once accepted"
    )

    val awFire = axi.awvalid && axi.awready
    val awAddr = RegEnable(axi.awaddr(19, 0), awFire)        // in-flight write target
    val bRise  = axi.bvalid && !RegNext(axi.bvalid, false.B) // write-commit cycle
    val written: Seq[Expr] = requires.map { sig =>
      val a = operandAddrOf(sig)
      val w = RegInit(false.B)
      when(bRise && (axi.bresp === OKAY.U) && (awAddr === a.U)) { w := true.B }
      B(w, f"written_0x$a%x"): Expr
    }
    val operandsWritten = NamedProp.assume(
      s"gen_commit_operands_written_$hex",
      B(pending, "pending") ==> written.reduce(_ && _),
      s"a commit at $hex fires only with every operand written since reset"
    )
    Seq(retires, operandsWritten)
  }

  // Access-only -> properties
  private def genAccess(addr: Long, a: Access): Seq[NamedProp] = Seq.empty

  // Flush; must be the last statement of the spec body.
  protected def done(): Unit = {
    if (ledger.nonEmpty) println("[mallet] memory map:\n" + ledger.map("  " + _).mkString("\n"))
    mallet(items.flatMap(_()).toSeq: _*)
    items.clear(); roleReg.clear(); ledger.clear()
  }
}