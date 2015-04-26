package hwacha

import Chisel._
import DataGating._

class RFWritePort extends VXUBundle {
  val addr = UInt(width = log2Up(nSRAM))
  val data = Bits(width = wBank)
  val mask = Bits(width = wBank/8)
}

class BankRegfile extends VXUModule {
  val io = new Bundle {
    val op = new BankOpIO().flip
    val global = new BankRWIO
    val local = new Bundle {
      val rdata = Vec.fill(nLOPL){new BankReadEntry().asOutput}
      val wdata = new BankWriteEntry().asInput
    }
  }

  val sram_rf = Mem(Bits(width = wBank), nSRAM, seqRead = true)
  val ff_rf = Mem(Bits(width = wBank), nFF)
  val opl = Mem(Bits(width = wBank), nGOPL+nLOPL)

  // SRAM RF read port
  val sram_raddr = Reg(Bits())
  when (io.op.sram.read.valid) { sram_raddr := io.op.sram.read.bits.addr }
  val sram_rdata = sram_rf(sram_raddr)

  // SRAM RF write port
  val sram_warb = Module(new Arbiter(new RFWritePort, 3))

  sram_warb.io.in(0).valid := io.op.sram.write.valid
  sram_warb.io.in(0).bits.addr := io.op.sram.write.bits.addr
  sram_warb.io.in(0).bits.data :=
    Mux(io.op.sram.write.bits.selg,
      MuxLookup(io.op.sram.write.bits.wsel, Bits(0), (0 until nWSel).map {
        i => UInt(i) -> io.global.wdata(i).d }),
      io.local.wdata.d)
  sram_warb.io.in(0).bits.mask := FillInterleaved(regLen/8, io.op.sram.write.bits.pred)
  assert(!io.op.sram.write.valid || sram_warb.io.in(0).ready, "this sram write port should always be ready")

  sram_warb.io.in(1).valid := io.global.bwq.mem.valid && !io.global.bwq.mem.bits.selff
  sram_warb.io.in(1).bits.addr := io.global.bwq.mem.bits.saddr()
  sram_warb.io.in(1).bits.data := io.global.bwq.mem.bits.data
  sram_warb.io.in(1).bits.mask := io.global.bwq.mem.bits.mask

  sram_warb.io.in(2).valid := io.global.bwq.fu.valid && !io.global.bwq.fu.bits.selff
  sram_warb.io.in(2).bits.addr := io.global.bwq.fu.bits.saddr()
  sram_warb.io.in(2).bits.data := io.global.bwq.fu.bits.data
  sram_warb.io.in(2).bits.mask := io.global.bwq.fu.bits.mask

  sram_warb.io.out.ready := Bool(true) // can always write the register file
  when (sram_warb.io.out.valid) {
    sram_rf.write(
      sram_warb.io.out.bits.addr,
      sram_warb.io.out.bits.data,
      FillInterleaved(8, sram_warb.io.out.bits.mask))
  }

  // FF RF read port
  val ff_raddr = (0 until nFFRPorts).map { i => dgate(io.op.ff.read(i).valid, io.op.ff.read(i).bits.addr) }
  val ff_rdata = (0 until nFFRPorts).map { i => ff_rf(ff_raddr(i)) }

  // FF RF write port
  val ff_warb = Module(new Arbiter(new RFWritePort, 3))

  ff_warb.io.in(0).valid := io.op.ff.write.valid
  ff_warb.io.in(0).bits.addr := io.op.ff.write.bits.addr
  ff_warb.io.in(0).bits.data :=
    Mux(io.op.ff.write.bits.selg,
      MuxLookup(io.op.ff.write.bits.wsel, Bits(0), (0 until nWSel).map {
        i => UInt(i) -> io.global.wdata(i).d }),
      io.local.wdata.d)
  ff_warb.io.in(0).bits.mask := FillInterleaved(regLen/8, io.op.ff.write.bits.pred)
  assert(!io.op.ff.write.valid || ff_warb.io.in(0).ready, "this ff write port should always be ready")

  ff_warb.io.in(1).valid := io.global.bwq.mem.valid && io.global.bwq.mem.bits.selff
  ff_warb.io.in(1).bits.addr := io.global.bwq.mem.bits.faddr()
  ff_warb.io.in(1).bits.data := io.global.bwq.mem.bits.data
  ff_warb.io.in(1).bits.mask := io.global.bwq.mem.bits.mask

  ff_warb.io.in(2).valid := io.global.bwq.fu.valid && io.global.bwq.fu.bits.selff
  ff_warb.io.in(2).bits.addr := io.global.bwq.fu.bits.faddr()
  ff_warb.io.in(2).bits.data := io.global.bwq.fu.bits.data
  ff_warb.io.in(2).bits.mask := io.global.bwq.fu.bits.mask

  ff_warb.io.out.ready := Bool(true) // can always write the register file
  when (ff_warb.io.out.valid) {
    ff_rf.write(
      ff_warb.io.out.bits.addr,
      ff_warb.io.out.bits.data,
      FillInterleaved(8, ff_warb.io.out.bits.mask))
  }

  // BWQ
  io.global.bwq.mem.ready :=
    !io.global.bwq.mem.bits.selff && sram_warb.io.in(1).ready ||
     io.global.bwq.mem.bits.selff && ff_warb.io.in(1).ready

  io.global.bwq.fu.ready :=
    !io.global.bwq.fu.bits.selff && sram_warb.io.in(2).ready ||
     io.global.bwq.fu.bits.selff && ff_warb.io.in(2).ready

  // Operand Latches
  (0 until nGOPL) foreach { i =>
    when (io.op.opl.global(i).valid) {
      opl.write(
        UInt(i),
        Mux(io.op.opl.global(i).bits.selff, ff_rdata(i % nFFRPorts), sram_rdata),
        FillInterleaved(regLen, io.op.opl.global(i).bits.pred))
    }
    io.global.rdata(i).d := dgate(io.op.xbar(i).valid, opl(i))
  }
  (0 until nLOPL) foreach { i =>
    when (io.op.opl.local(i).valid) {
      opl.write(
        UInt(nGOPL+i),
        Mux(io.op.opl.local(i).bits.selff, ff_rdata(i % nFFRPorts), sram_rdata),
        FillInterleaved(regLen, io.op.opl.local(i).bits.pred))
    }
    io.local.rdata(i).d := opl(nGOPL+i)
  }
}
