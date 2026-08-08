// SPDX-License-Identifier: Apache-2.0
package fp

import chisel3._
import chisel3.util._
import hardfloat._

// CPU-facing opcode encoding (as opposed to FPOpMode, for elaboration time)
object FPUOpcode {
  val ADD  = 0
  val SUB  = 1
  val MUL  = 2
  val DIV  = 3
  val SQRT = 4
  val byName: Map[String, Int] = Map("ADD"->ADD, "SUB"->SUB, "MUL"->MUL, "DIV"->DIV, "SQRT"->SQRT)

  def isComb(op: UInt): Bool = op === ADD.U || op === SUB.U || op === MUL.U
  def isSqrt(op: UInt): Bool = op === SQRT.U
}

// command going into a lane, tag is assigned by CPU
class LaneCmd(val bw: Int, val tagBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val opcode = UInt(3.W)
  val a = UInt(bw.W)
  val b = UInt(bw.W)
}

// result coming out of a lane
class LaneResult(val bw: Int, val tagBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val result = UInt(bw.W)
  val flags = UInt(5.W)
}

// wrapper for FPSeqUnit (DIV/SQRT) with Decoupled and tags
class SeqLane(val expW: Int = 8, val sigW: Int = 24, val tagBits: Int = 8) extends Module {
  val bw = expW + sigW
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new LaneCmd(bw, tagBits)))
    val out = Decoupled(new LaneResult(bw, tagBits))
  })

  val u = Module(new FPSeqUnit(expW, sigW))
  val tagReg = Reg(UInt(tagBits.W)) // need to store tag while computing

  // write
  io.in.ready := u.io.in.ready
  u.io.in.valid := io.in.valid
  u.io.in.bits.a := io.in.bits.a
  u.io.in.bits.b := io.in.bits.b
  u.io.in.bits.sqrtOp := FPUOpcode.isSqrt(io.in.bits.opcode)
  when (u.io.in.fire) {
    tagReg := io.in.bits.tag
  }

  // read
  io.out.valid := u.io.out.valid
  u.io.out.ready := io.out.ready
  io.out.bits.tag := tagReg
  io.out.bits.result := u.io.out.bits.data
  io.out.bits.flags := u.io.out.bits.exceptionFlags
}

// wrapper for AddRecFN and MulRecFN (ADD/SUB/MUL) with Decoupled and tags
class CombLane(val expW: Int = 8, val sigW: Int = 24, val tagBits: Int = 8) extends Module {
  val bw = expW + sigW
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new LaneCmd(bw, tagBits)))
    val out = Decoupled(new LaneResult(bw, tagBits))
  })

  val add = Module(new AddRecFN(expW, sigW))
  add.io.a := recFNFromFN(expW, sigW, io.in.bits.a)
  add.io.b := recFNFromFN(expW, sigW, io.in.bits.b)
  add.io.subOp := io.in.bits.opcode === FPUOpcode.SUB.U
  add.io.roundingMode := 0.U
  add.io.detectTininess := 1.U

  val mul = Module(new MulRecFN(expW, sigW))
  mul.io.a := recFNFromFN(expW, sigW, io.in.bits.a)
  mul.io.b := recFNFromFN(expW, sigW, io.in.bits.b)
  mul.io.roundingMode := 0.U
  mul.io.detectTininess := 1.U

  val isMul = io.in.bits.opcode === FPUOpcode.MUL.U
  val resRec = Mux(isMul, mul.io.out, add.io.out)
  val flags = Mux(isMul, mul.io.exceptionFlags, add.io.exceptionFlags)

  // backpressure reg
  val outQ = Module(new Queue(new LaneResult(bw, tagBits), 1, pipe = true))
  io.in.ready := outQ.io.enq.ready
  outQ.io.enq.valid := io.in.valid
  outQ.io.enq.bits.tag := io.in.bits.tag
  outQ.io.enq.bits.result := fNFromRecFN(expW, sigW, resRec)
  outQ.io.enq.bits.flags := flags
  io.out <> outQ.io.deq
}

// floating point unit with ADD/SUB/MUL/DIV/SQRT, 1 combinational unit and numSeqLanes sequential units
class FPUCore(val expW: Int = 8, val sigW: Int = 24, val tagBits: Int = 8,
              val numSeqLanes: Int = 4, val combQSize: Int = 8,
              val seqQSize: Int = 8, val outQSize: Int = 16) extends Module {
  val bw = expW + sigW
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new LaneCmd(bw, tagBits)))
    val out = Decoupled(new LaneResult(bw, tagBits))
    val combCount = Output(UInt(32.W))
    val seqCount = Output(UInt(32.W))
    val outCount = Output(UInt(32.W))
  })

  // --- TWO INPUT QUEUES --- 
  val combInQ = Module(new Queue(new LaneCmd(bw, tagBits), combQSize))
  val seqInQ = Module(new Queue(new LaneCmd(bw, tagBits), seqQSize))
  // route via opcode 
  val toCombInQ = FPUOpcode.isComb(io.in.bits.opcode)
  combInQ.io.enq.valid := io.in.valid && toCombInQ
  combInQ.io.enq.bits  := io.in.bits
  seqInQ.io.enq.valid  := io.in.valid && !toCombInQ
  seqInQ.io.enq.bits   := io.in.bits
  // backpressure for ready from InQs
  io.in.ready := Mux(toCombInQ, combInQ.io.enq.ready, seqInQ.io.enq.ready)

  // --- ASSIGN TO LANES ---
  // comb lane, just one
  val combLane = Module(new CombLane(expW, sigW, tagBits))
  combLane.io.in <> combInQ.io.deq
  // seq lanes split by priority encoder
  val seqLanes = Seq.fill(numSeqLanes) { Module(new SeqLane(expW, sigW, tagBits)) }
  val readyVec = VecInit(seqLanes.map(_.io.in.ready))
  val nextLaneIndex = PriorityEncoder(readyVec)
  for (i <- 0 until numSeqLanes) {
    seqLanes(i).io.in.valid := seqInQ.io.deq.valid && (nextLaneIndex === i.U)
    seqLanes(i).io.in.bits := seqInQ.io.deq.bits
  }
  seqInQ.io.deq.ready := readyVec.asUInt.orR // no backpressure if any SeqLane free

  // --- ONE OUTPUT QUEUE ---
  // (seqLanes + combLane) --> round-robin arb --> completionQ --> downstream
  val arb = Module(new RRArbiter(new LaneResult(bw, tagBits), numSeqLanes + 1))
  val completionQ = Module(new Queue(new LaneResult(bw, tagBits), outQSize))
  // merge lanes
  for (i <- 0 until numSeqLanes + 1) {
    if (i == numSeqLanes) {
      arb.io.in(i) <> combLane.io.out
    } else {
      arb.io.in(i) <> seqLanes(i).io.out
    }
  }
  completionQ.io.enq <> arb.io.out
  io.out <> completionQ.io.deq

  io.combCount := combInQ.io.count
  io.seqCount := seqInQ.io.count
  io.outCount := completionQ.io.count 
}