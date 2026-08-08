// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3._
import scala.collection.mutable
import java.security.MessageDigest
import chisel3.util.log2Ceil

object FormalUtils {
  /** Reset-aligned warm-up mask for guarding past(n).
    * Returns false for first n cycles after reset deasserts and true thereafter.
    *
    * This is needed because CIRCT lowers ltl.past to a seq.shiftreg with NO reset, 
    * so the shift register keeps sampling while the circuit is held in reset. 
    * The first post-reset cycle therefore sees a past-value
    * from a cycle that never legitimately happened, and "past(a) |-> b"
    * fires a spurious counterexample at that boundary.
    * 
    * Example: AssertProperty( warmedUp(1).and(io.in.fire.past(1)) |-> (state === sCompute) )
    */
  def warmedUp(n: Int): Bool = {
    require(n >= 0, "warm-up depth must be non-negative")
    if (n == 0) true.B
    else {
      val c = RegInit(0.U(log2Ceil(n + 1).W))
      when(c =/= n.U) { c := c + 1.U }
      c === n.U
    }
  }
}

// Collects the properties elaborated across a run and writes the sidecar that
// lets the Python scripts attribute a BTOR2 verdict with an English sentence.
object MalletRegistry {

  final case class Entry(
    module: String,
    label:  String,
    name:   String,
    prop:   Prop,
    note:   String,
    idx:    Int,
    maxPast: Int,
    kind:   PropKind,
    coverLabel: Option[String]   // reachability cover for this property, if any
  )

  private val entries = mutable.ArrayBuffer.empty[Entry]

  var coversEnabled: Boolean = false

  def clear(): Unit = entries.clear()

  def all: Seq[Entry] = entries.toSeq

  def forModule(m: String): Seq[Entry] = entries.filter(_.module == m).toSeq

  /** [Claude] Consistent identity for a property.
    *
    * `MT_<NN>_<name>_<hash8>`:
    *   - `NN`     preserves readable source order in the report
    *   - `name`   makes the emitted IR self-describing when debugging by grep
    *   - `hash8`  is sha1 of the canonical AST, so identity survives reordering
    *              and changes detectably when the property itself is edited
    *
    */
  def labelFor(idx: Int, name: String, prop: Prop): String = {
    val sha = MessageDigest.getInstance("SHA-1").digest(prop.canon.getBytes("UTF-8"))
    val hash8 = sha.take(4).map(b => f"${b & 0xff}%02x").mkString
    f"MT_$idx%02d_${sanitize(name)}_$hash8"
  }

  private def sanitize(s: String): String =
    s.map(c => if (c.isLetterOrDigit || c == '_') c else '_')

  private[mallet] def register(
    module: String,
    label:  String,
    name:   String,
    prop:   Prop,
    note:   String,
    idx:    Int,
    kind:   PropKind,
    coverLabel: Option[String]
  ): Unit = entries += Entry(module, label, name, prop, note, idx, prop.maxPast, kind, coverLabel)

  // Write the per-module sidecar; call after emitCHIRRTLFile returns
  def writeSidecar(dir: String, chiselVersion: String = "7.13.0"): Unit = {
    if (entries.isEmpty) return
    val d = os.Path(dir, os.pwd)
    os.makeDir.all(d)

    entries.groupBy(_.module).foreach { case (module, es) =>
      val props = es.sortBy(_.idx).map { e =>
        ujson.Obj(
          "label"   -> e.label,
          "name"    -> e.name,
          "idx"     -> e.idx,
          "maxPast" -> e.maxPast,
          "kind"    -> (e.kind match { case AssertK => "assert"; case AssumeK => "assume" }),
          "shape"   -> (e.prop match {
                          case _: Implies => "implies"
                          case _: Always  => "always"
                        }),
          "nl"      -> Render.toNL(e.prop),
          "canon"   -> e.prop.canon,
          "coverLabel" -> (e.coverLabel match { case Some(c) => ujson.Str(c); case None => ujson.Null }),
          "note"    -> e.note
        )
      }
      val obj = ujson.Obj(
        "module" -> module,
        "chisel" -> chiselVersion,
        "props"  -> ujson.Arr.from(props)
      )
      os.write.over(d / s"$module.props.json", ujson.write(obj, indent = 2))
      println(s"[mallet] wrote ${d / s"$module.props.json"} (${props.length} properties)")
    }
  }
}

// Chisel Module mix-in to give it property-elaboration machinery 
trait MalletProperties { this: chisel3.Module =>

  // Override to switch past(n) realisation; see PastBackend
  def pastBackend: PastBackend = LtlPast

  private val warmCache = mutable.Map.empty[Int, Bool]

  private def warm(n: Int): Bool =
    if (n == 0) true.B else warmCache.getOrElseUpdate(n, FormalUtils.warmedUp(n))

  private var propIdx = 0

  protected def mallet(ps: NamedProp*): Unit = {
    val module = this.desiredName
    ps.foreach { np =>
      propIdx += 1
      val label = MalletRegistry.labelFor(propIdx, np.name, np.prop)
      Render.toChisel(label, np.prop, warm, np.kind, pastBackend)
      val coverLabel =
        if (np.kind == AssertK) Render.coverLabelFor(label, np.prop) else None
      if (MalletRegistry.coversEnabled) coverLabel.foreach(cl => Render.emitCover(cl, np.prop, warm))
      MalletRegistry.register(module, label, np.name, np.prop, np.note, propIdx, np.kind, coverLabel)
    }
  }

  // Attach a whole protocol contract set to this module
  protected def contract[B](set: _root_.mallet.contract.ContractSet[B], bundle: B): Unit =
    mallet(set.properties(bundle): _*)
}
