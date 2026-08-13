// SPDX-License-Identifier: Apache-2.0
package arithmetic

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._
import mallet.FormalUtils._
import _root_.circt.stage.ChiselStage

class MacIn(w: Int) extends Bundle {
  val a    = UInt(w.W)
  val b    = UInt(w.W)
  val last = Bool()
}

class Mac(val width: Int, val accWidth: Int) extends Module {
  require(accWidth >= 2 * width, "accWidth must hold at least one full product")

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MacIn(width)))
    val out = Decoupled(UInt(accWidth.W))
  })

  val sIdle :: sCompute :: sDone :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val aReg    = Reg(UInt(width.W))
  val bReg    = Reg(UInt(width.W))
  val lastReg = RegInit(false.B)
  val accReg  = RegInit(0.U(accWidth.W))

  io.in.ready  := state === sIdle
  io.out.valid := state === sDone
  io.out.bits  := accReg

  switch(state) {
    is(sIdle) {
      when(io.in.fire) {
        aReg    := io.in.bits.a
        bReg    := io.in.bits.b
        lastReg := io.in.bits.last
        state   := sCompute
      }
    }
    is(sCompute) {
      accReg := accReg + (aReg * bReg)
      state  := Mux(lastReg, sDone, sIdle)
    }
    is(sDone) {
      when(io.out.fire) {
        accReg := 0.U
        state  := sIdle
      }
    }
  }

  // AssertProperty(state =/= 3.U) // reachability: no 4th state
  // AssertProperty(io.out.valid |-> (state === sDone))
  // AssertProperty(io.out.valid |-> (io.out.bits === accReg))
  // AssertProperty(!(io.in.ready && io.out.valid))

  // // functionally the same as:
  // // AssertProperty(io.in.fire |=> (state === sCompute))
  // // but support for |=> no longer exists.

  // // the following assertion would be violated on step 1:
  // // AssertProperty(io.in.fire.past(1) |-> (state === sCompute))
  // // since the shiftreg implied by past() would be uninitialized
  // // BELOW 2 LINES NOW OUT OF DATE, SEE BELOW
  // // val started = RegInit(false.B); started := true.B
  // // AssertProperty(started.and(io.in.fire.past(1)) |-> (state === sCompute))
  
  // AssertProperty(warmedUp(1).and(io.in.fire.past(1)) |-> (state === sCompute))
}

object MacMain extends App {
  ChiselStage.emitSystemVerilogFile(
    new Mac(width = 8, accWidth = 32),
    args = Array("--target-dir", "generated/arithmetic"),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    ),
  )
}

object MacChirrtlMain extends App {
  ChiselStage.emitCHIRRTLFile(
    new Mac(width = 8, accWidth = 32),
    args = Array("--target-dir", "generated/arithmetic/chirrtl")
  )
}