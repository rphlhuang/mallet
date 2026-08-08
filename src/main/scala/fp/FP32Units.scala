// SPDX-License-Identifier: Apache-2.0
package fp

import chisel3._
import chisel3.util._
import hardfloat._
import _root_.circt.stage.ChiselStage

object FPOpMode {
  trait Mode
  case object ADD extends Mode
  case object SUB extends Mode
  case object MUL extends Mode
  case object DIVSQRT extends Mode
}

class FPCombUnit(val expW: Int = 8, val sigW: Int = 24, val mode: FPOpMode.Mode = FPOpMode.ADD) extends Module {
  val bw = expW + sigW
  val io = IO(new Bundle {
    val in_a = Input(UInt(bw.W))
    val in_b = Input(UInt(bw.W))
    val out = Output(UInt(bw.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  require(mode == FPOpMode.MUL ||
          mode == FPOpMode.ADD ||
          mode == FPOpMode.SUB, 
          "mode must be MUL, ADD, or SUB for FPCombUnit"
         )

  override def desiredName = s"FP${mode}_${expW}_$sigW"
  if (mode == FPOpMode.MUL) {
    val opRecFN = Module(new MulRecFN(expW, sigW))
    opRecFN.io.a := recFNFromFN(expW, sigW, io.in_a)
    opRecFN.io.b := recFNFromFN(expW, sigW, io.in_b)
    opRecFN.io.roundingMode := 0.U
    opRecFN.io.detectTininess := 1.U
    io.exceptionFlags := opRecFN.io.exceptionFlags 
    io.out := fNFromRecFN(expW, sigW, opRecFN.io.out)
  } else if (mode == FPOpMode.ADD || mode == FPOpMode.SUB) {
    val opRecFN = Module(new AddRecFN(expW, sigW))
    opRecFN.io.subOp := (mode == FPOpMode.SUB).B
    opRecFN.io.a := recFNFromFN(expW, sigW, io.in_a)
    opRecFN.io.b := recFNFromFN(expW, sigW, io.in_b)
    opRecFN.io.roundingMode := 0.U
    opRecFN.io.detectTininess := 1.U
    io.exceptionFlags := opRecFN.io.exceptionFlags 
    io.out := fNFromRecFN(expW, sigW, opRecFN.io.out)
  }
}

class FPSeqIn(val bw: Int) extends Bundle {
  val a = Input(UInt(bw.W))
  val b = Input(UInt(bw.W))
  val sqrtOp = Input(Bool())
}

class FPSeqOut(val bw: Int) extends Bundle {
  val data = Output(UInt(bw.W))
  val exceptionFlags = Output(UInt(5.W))
}

class FPSeqUnit(val expW: Int = 8, val sigW: Int = 24, val mode: FPOpMode.Mode = FPOpMode.DIVSQRT) extends Module {
  val bw = expW + sigW
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FPSeqIn(bw)))
    val out = Decoupled(new FPSeqOut(bw))
  })

  require(mode == FPOpMode.DIVSQRT, "mode must be DIVSQRT for FPSeqUnit")
  override def desiredName = s"FP${mode}_${expW}_$sigW"
  val opRecFN = Module(new DivSqrtRecFN_small(expW, sigW, options = 0))

  // output skid reg (allows for backpressure)
  val buf = Reg(UInt(bw.W))
  val bufFull = RegInit(false.B)
  val dutValid = opRecFN.io.outValid_sqrt || opRecFN.io.outValid_div
  when (dutValid && !io.out.ready) {
    buf := fNFromRecFN(expW, sigW, opRecFN.io.out)
    bufFull := true.B
  }
  when (io.out.fire) {
    bufFull := false.B
  }

  // upstream
  io.in.ready := opRecFN.io.inReady && !bufFull
  opRecFN.io.inValid := io.in.valid
  opRecFN.io.sqrtOp := io.in.bits.sqrtOp
  opRecFN.io.a := recFNFromFN(expW, sigW, io.in.bits.a)
  when (io.in.bits.sqrtOp) {
    opRecFN.io.b := DontCare
  }.otherwise {
    opRecFN.io.b := recFNFromFN(expW, sigW, io.in.bits.b)
  }
  opRecFN.io.roundingMode := 0.U
  opRecFN.io.detectTininess := 1.U
  
  // downstream (skip roundingModeOut for now)
  io.out.valid := dutValid || bufFull
  io.out.bits.data := Mux(bufFull, buf, fNFromRecFN(expW, sigW, opRecFN.io.out))
  io.out.bits.exceptionFlags := opRecFN.io.exceptionFlags 
}

object FPAddMain extends App {
  ChiselStage.emitSystemVerilogFile(
    new FPCombUnit(8, 24, FPOpMode.ADD), // 8 exp, (23 + 1 implcit leading bit) significand = single-precision
    args = Array("--target-dir", "generated/fp"),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    ),
  )
}

object FPSubMain extends App {
  ChiselStage.emitSystemVerilogFile(
    new FPCombUnit(8, 24, FPOpMode.SUB),
    args = Array("--target-dir", "generated/fp"),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    ),
  )
}

object FPMulMain extends App {
  ChiselStage.emitSystemVerilogFile(
    new FPCombUnit(8, 24, FPOpMode.MUL),
    args = Array("--target-dir", "generated/fp"),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    ),
  )
}

object FPDivSqrtMain extends App {
  ChiselStage.emitSystemVerilogFile(
    new FPSeqUnit(8, 24, FPOpMode.DIVSQRT), 
    args = Array("--target-dir", "generated/fp"),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays",
    ),
  )
}
