// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import scala.collection.mutable
import axi.{AxiLite32, HasAxiLite32IO}
import axi.AxiLiteResp.{OKAY, SLVERR}
import mallet.contract.{AxiLite32Contract, ContractSet, MapMonitors}

/** The infix syntax as an interface.
  * Subclass of the DUT (the only way I could think of doing it cleanly).
  * MalletSpec adds no new lowering, just convenience.
  *
  * done() is a workaround due to how Chisel rendering works;
  * registration emits hardware but hardware may only be added while the module is still open,
  * so done() flushes at the end of the spec body.
  *
  * A self-imposed rule for scope is that all annotations only interact at the
  * interface level: so addresses in, addresses out, no references to inside the DUT.
  */
trait MalletSpec extends MalletProperties { this: chisel3.Module with HasAxiLite32IO =>

  // Source-ordered generators, run at done()
  private val items  = mutable.ArrayBuffer.empty[() => Seq[NamedProp]]
  private val decls  = mutable.ArrayBuffer.empty[Decl]
  private val ledger = mutable.ArrayBuffer.empty[String]

  private[mallet] def enqueue(np: NamedProp): Unit = { items += (() => Seq(np)); () }

  // ---- manual tier: property --> AssertProperty, assume --> AssumeProperty ------
  protected def property(name: String)(e: Prop): Unit =
    enqueue(NamedProp.assert(name, e, "", Manual))
  protected def property(name: String, desc: String)(e: Prop): Unit =
    enqueue(NamedProp.assert(name, e, desc, Manual))

  protected def assume(name: String)(e: Prop): Unit =
    enqueue(NamedProp.assume(name, e, "", Manual))
  protected def assume(name: String, desc: String)(e: Prop): Unit =
    enqueue(NamedProp.assume(name, e, desc, Manual))

  import scala.language.implicitConversions
  import scala.language.experimental.macros // do this pre-elaboration, with a Macro to extract name before compile
  protected implicit def boolToExpr(b: Bool): B = macro MalletMacros.boolToExprImpl


  // ---- transport tier ------------------------------------------------------------
  protected val AxiLite32Slave: ContractSet[AxiLite32] = AxiLite32Contract
  protected implicit class ConformsOps(bundle: AxiLite32) {
    def conformsTo(set: ContractSet[AxiLite32]): Unit = {
      val ps = set.properties(bundle).map(_.copy(tier = Transport)) // allocates monitor hardware asap
      items += (() => ps)
    }
  }


  // ---- memory-map tier ------------------------------------------------------------
  private final class Decl(val addr: Long, val access: Access, val roleLabel: String) {
    var gatedBy:  Option[Long] = None
    var setBy:    Option[Long] = None
    var requires: Seq[Long]    = Seq.empty

    def note: String = {
      val rel =
        gatedBy.map(a => f" gatedBy 0x$a%x").getOrElse("") +
        setBy.map(a => f" setBy 0x$a%x").getOrElse("") +
        (if (requires.nonEmpty) requires.map(a => f"0x$a%x").mkString(" requiring (", ", ", ")") else "")
      // a bare access mode is its own label; don't print "RO (RO)"
      val what = if (roleLabel == access.label) access.label else s"$roleLabel (${access.label})"
      f"0x$addr%02x  $what%-18s$rel"
    }
  }

  private def declare(addr: Long, access: Access, label: String): Decl = {
    require(
      !decls.exists(_.addr == addr),
      f"address 0x$addr%x is annotated twice; each address gets exactly one access mode or role"
    )
    val d = new Decl(addr, access, label)
    decls += d
    d
  }

  protected implicit class AddressOps(addr: Long) {
    def is(a: Access):       AccessBuilder = new AccessBuilder(declare(addr, a, a.label))
    def is(r: Status.type):  StatusBuilder = new StatusBuilder(declare(addr, r.access, r.label))
    def is(r: Result.type):  ResultBuilder = new ResultBuilder(declare(addr, r.access, r.label))
    def is(r: Commit.type):  CommitBuilder = new CommitBuilder(declare(addr, r.access, r.label))
  }

  protected class AccessBuilder(d: Decl) {
    def gatedBy(statusAddr: Long): Unit = { d.gatedBy = Some(statusAddr) }
  }
  protected class StatusBuilder(d: Decl) {
    def setBy(commitAddr: Long): Unit = { d.setBy = Some(commitAddr) }
  }
  protected class ResultBuilder(d: Decl) {
    def gatedBy(statusAddr: Long): Unit = { d.gatedBy = Some(statusAddr) }
  }
  protected class CommitBuilder(d: Decl) {
    def requiring(operandAddrs: Long*): Unit = { d.requires = operandAddrs }
  }

  // ---- error policy ------------------------------------------------------------

  private var policyReadErrors  = true
  private var policyWriteErrors = true

  // opt out of error-response enforcement for a map that does not want one
  protected def errorPolicy(readErrors: Boolean = true, writeErrors: Boolean = true): Unit = {
    policyReadErrors  = readErrors
    policyWriteErrors = writeErrors
  }

  // ---- generation ------------------------------------------------------------

  private lazy val mon = new MapMonitors(S.AXI)

  private def hex(a: Long): String = f"0x$a%x"

  private def readOf(a: Long): Expr =
    B(S.AXI.rvalid, "rvalid") && Cmp(CmpOp.Eq, Sig(mon.arAddr, "arAddr"), Lit(BigInt(a), hex(a)))

  private def writeOf(a: Long): Expr =
    B(S.AXI.bvalid, "bvalid") && Cmp(CmpOp.Eq, Sig(mon.awAddr, "awAddr"), Lit(BigInt(a), hex(a)))

  private def rrespIs(v: Int, nm: String): Expr =
    Cmp(CmpOp.Eq, Sig(S.AXI.rresp, "rresp"), Lit(BigInt(v), nm))
  private def brespIs(v: Int, nm: String): Expr =
    Cmp(CmpOp.Eq, Sig(S.AXI.bresp, "bresp"), Lit(BigInt(v), nm))

  private def mm(name: String, e: Prop, note: String) = NamedProp.assert(name, e, note, MemMap)

  private def genDecl(d: Decl): Seq[NamedProp] = {
    val a  = d.addr
    val hx = hex(a)
    val ps = mutable.ArrayBuffer.empty[NamedProp]

    // access-mode obligations
    // note that we cannot enfoce OKAYs, since real errors (e.g. backpressure, waiting) could produce ERRs
    if (!d.access.writable && policyWriteErrors)
      ps += mm(s"mm_write_errs_$hx",
               writeOf(a) ==> brespIs(SLVERR, "SLVERR"),
               s"a write to $hx must be refused; it is ${d.access.label}")

    if (!d.access.readable && policyReadErrors)
      ps += mm(s"mm_read_errs_$hx",
               readOf(a) ==> rrespIs(SLVERR, "SLVERR"),
               s"a read of $hx must be refused; it is ${d.access.label}")

    // readable addresss should answer OKAY
    if (d.access.readable && !d.access.destructiveRead && d.gatedBy.isEmpty)
      ps += mm(s"mm_read_ok_$hx",
               readOf(a) ==> rrespIs(OKAY, "OKAY"),
               s"a read of $hx succeeds")

    // readback: remember what you wrote, and it should be maintained (pure storage)
    if (d.access.readbackGuaranteed) {
      val (sv, sd) = mon.shadow(a)
      ps += mm(s"mm_readback_$hx",
               (readOf(a) && B(sv, s"written_$hx")) ==>
                 Cmp(CmpOp.Eq, Sig(S.AXI.rdata, "rdata"), Sig(sd, s"lastWritten_$hx")),
               s"a read of $hx returns the last value written to it")
    }

    // read gating: reading a result before the status address reported ready must be refused
    d.gatedBy.foreach { s =>
      val last = mon.lastRead(s)
      ps += mm(s"mm_gated_$hx",
               (readOf(a) && Cmp(CmpOp.Eq, Slice(last, 0, 0, s"lastStatus_${hex(s)}"), Lit(BigInt(0), "0"))) ==>
                 rrespIs(SLVERR, "SLVERR"),
               s"a read of $hx while the last read of ${hex(s)} reported not-ready must be refused")
    }

    // provenance: status never reports ready unless the commit address was actually written
    d.setBy.foreach { c =>
      ps += mm(s"mm_provenance_$hx",
               (readOf(a) && Cmp(CmpOp.Eq, Slice(S.AXI.rdata, 0, 0, "rdata"), Lit(BigInt(1), "1"))) ==>
                 B(mon.writtenSince(c), s"written_${hex(c)}"),
               s"$hx reports ready only if ${hex(c)} was written since reset")
    }

    // write ordering: an ASSUMPTION on the master, which could help with manual properties
    if (d.requires.nonEmpty) {
      val written: Seq[Expr] =
        d.requires.map(o => B(mon.writtenSince(o), s"written_${hex(o)}"): Expr)
      val awHit =
        B(mon.awFire, "awFire") &&
        Cmp(CmpOp.Eq, Slice(S.AXI.awaddr, 19, 0, "awaddr"), Lit(BigInt(a), hx))
      ps += NamedProp.assume(
        s"mm_requires_$hx",
        awHit ==> written.reduce(_ && _),
        s"the master writes $hx only after writing ${d.requires.map(hex).mkString(", ")}",
        MemMap
      )
    }

    ps.toSeq
  }

  // decode completeness: anything outside the declared map must be refused
  private def genUnmapped(): Seq[NamedProp] = {
    if (decls.isEmpty) return Seq.empty
    val addrs = decls.map(_.addr).toSeq.sorted
    def outside(sig: UInt, nm: String): Expr =
      // Neq directly rather than Not(Eq): normalization would absorb it anyway, but
      // the English renderer reads the RAW ast, and "differs from" beats
      // "it is not the case that ... equals ..." seven times in a row.
      addrs.map(a => Cmp(CmpOp.Neq, Sig(sig, nm), Lit(BigInt(a), hex(a))): Expr).reduce(_ && _)
    val listed = addrs.map(hex).mkString(", ")

    val ps = mutable.ArrayBuffer.empty[NamedProp]
    if (policyReadErrors)
      ps += mm("mm_unmapped_read",
               (B(S.AXI.rvalid, "rvalid") && outside(mon.arAddr, "arAddr")) ==> rrespIs(SLVERR, "SLVERR"),
               s"a read outside the declared map ($listed) must be refused")
    if (policyWriteErrors)
      ps += mm("mm_unmapped_write",
               (B(S.AXI.bvalid, "bvalid") && outside(mon.awAddr, "awAddr")) ==> brespIs(SLVERR, "SLVERR"),
               s"a write outside the declared map ($listed) must be refused")
    ps.toSeq
  }

  // flush properties when all is said and done!
  protected def done(): Unit = {
    decls.sortBy(_.addr).foreach { d => ledger += d.note }  // print the map in address order
    if (ledger.nonEmpty) println("[mallet] memory map:\n" + ledger.map("  " + _).mkString("\n"))
    val generated = decls.toSeq.flatMap(genDecl) ++ genUnmapped()
    mallet((generated ++ items.flatMap(_()).toSeq): _*)
    items.clear(); decls.clear(); ledger.clear()
  }
}
