// SPDX-License-Identifier: Apache-2.0
package mallet

import chisel3.Bool
import scala.reflect.macros.blackbox

/** [Claude] Implementation of MalletSpec's boolToExpr() implicit conversion.
  *
  * Wrap a bare Chisel Bool as mallet.B, using the call-site source text as the English alias
  * E.g. dut.io.out.fire is a unnamed node that Chisel/FIRRTL calls _T_11, 
  * capturing the source text renders it as "dut.io.out.fire" instead.
  */
object MalletMacros {

  def boolToExprImpl(c: blackbox.Context)(b: c.Expr[Bool]): c.Tree = {
    import c.universe._
    def startOf(p: Position): Int = try p.start catch { case _: Throwable => p.point }
    def endOf(p: Position):   Int = try p.end   catch { case _: Throwable => p.point }

    val positions = b.tree.collect { case node if node.pos != NoPosition => node.pos }
    val text: String =
      if (positions.isEmpty) showCode(b.tree) // synthesized tree: no source to quote
      else {
        val content = new String(positions.head.source.content)
        val start   = positions.map(startOf).min
        val end     = positions.map(endOf).max
        if (start >= 0 && end <= content.length && start < end)
          content.substring(start, end).trim
        else showCode(b.tree)
      }

    q"_root_.mallet.B($b, $text)"
  }
}