package chipyard

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam, StringParam}

import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{M_XRD, M_XWR, PRV}

class LDLTBlackBox(val m: Int, val n: Int) extends BlackBox(Map("M" -> IntParam(m), "N" -> IntParam(n))) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    
    val start = Input(Bool())
    val done = Output(Bool())
    val busy = Output(Bool())
    
    val rows = Input(UInt(64.W))
    val cols = Input(UInt(64.W))
    
    val data_in = Input(UInt(64.W))
    val data_in_valid = Input(Bool())
    val data_in_ready = Output(Bool())
    
    val data_out = Output(UInt(64.W))
    val data_out_valid = Output(Bool())
    val data_out_ready = Input(Bool())
  })
  
  addResource("/vsrc/LDLTBlackBox.v")
}

class LDLTAccelerator(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) {
  override lazy val module = new LDLTAcceleratorModuleImp(this)
}

class LDLTAcceleratorModuleImp(outer: LDLTAccelerator)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) with HasCoreParameters {
  
  val maxM = 20
  val fixedN = 4
  
  val blackbox = Module(new LDLTBlackBox(maxM, fixedN))
  blackbox.io.clock := clock
  blackbox.io.reset := reset.asBool
  
  // Registers
  val busy = RegInit(false.B)
  val desc_addr = Reg(UInt(64.W))
  val m_reg = Reg(UInt(64.W))
  val n_reg = Reg(UInt(64.W))
  val A_ptr = Reg(UInt(64.W))
  val b_ptr = Reg(UInt(64.W))
  
  // DMA Counters
  val dma_addr = Reg(UInt(64.W))
  val dma_cnt = Reg(UInt(64.W))
  val dma_limit = Reg(UInt(64.W))
  
  object State extends ChiselEnum {
    val sIdle, sFetchDesc, sLoadA, sLoadB, sCompute, sStoreRes, sDone = Value
  }
  val state = RegInit(State.sIdle)
  
  // RoCC Interface
  io.cmd.ready := (state === State.sIdle)
  val is_check = io.cmd.valid && io.cmd.bits.inst.funct === 1.U
  val is_solve = io.cmd.valid && io.cmd.bits.inst.funct === 0.U
  
  io.resp.valid := (io.cmd.valid && is_check) || (state === State.sDone)
  io.resp.bits.rd := io.cmd.bits.inst.rd
  io.resp.bits.data := Mux(is_check, busy.asUInt, 0.U)
  io.busy := busy
  io.interrupt := false.B
  
  // Blackbox Connections
  blackbox.io.start := false.B
  blackbox.io.rows := m_reg
  blackbox.io.cols := n_reg
  blackbox.io.data_in := 0.U
  blackbox.io.data_in_valid := false.B
  blackbox.io.data_out_ready := false.B
  
  // Memory Request Defaults
  io.mem.req.valid := false.B
  io.mem.req.bits.addr := dma_addr
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := log2Ceil(8).U
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.phys := false.B // Use VM if enabled (VA=PA in baremetal)
  io.mem.req.bits.dprv := freechips.rocketchip.rocket.PRV.M.U   // Machine mode access
  io.mem.req.bits.data := 0.U
  io.mem.req.bits.mask := 0.U
  
  // Descriptor Fetch Counter
  val desc_cnt = RegInit(0.U(3.W))
  
  switch (state) {
    is (State.sIdle) {
      when (io.cmd.fire && is_solve) {
        busy := true.B
        desc_addr := io.cmd.bits.rs1
        state := State.sFetchDesc
        desc_cnt := 0.U
        dma_addr := io.cmd.bits.rs1
      }
    }
    
    is (State.sFetchDesc) {
      io.mem.req.valid := true.B
      io.mem.req.bits.addr := desc_addr + (desc_cnt << 3)
      
      when (io.mem.req.fire) {
        // Wait for resp
      }
      
      when (io.mem.resp.valid) {
        val data = io.mem.resp.bits.data
        switch (desc_cnt) {
          is (0.U) { A_ptr := data }
          is (1.U) { b_ptr := data }
          is (2.U) { m_reg := data }
          is (3.U) { 
            n_reg := data
            state := State.sLoadA
            dma_addr := A_ptr
            dma_cnt := 0.U
            dma_limit := m_reg * data // m * n
            blackbox.io.start := true.B // Start BB to accept data
          }
        }
        desc_cnt := desc_cnt + 1.U
      }
    }
    
    is (State.sLoadA) {
      // Read A from memory, feed to Blackbox
      // Simple lock-step: Req -> Resp -> BB
      // Optimization: Pipelining (omitted for simplicity)
      
      val pending_load = RegInit(false.B)
      
      when (!pending_load) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := A_ptr + (dma_cnt << 3)
        when (io.mem.req.fire) {
          pending_load := true.B
        }
      }
      
      when (io.mem.resp.valid) {
        blackbox.io.data_in := io.mem.resp.bits.data
        blackbox.io.data_in_valid := true.B
        // Wait for BB to accept
        // Note: This assumes BB is ready immediately or we need to hold
        // For simplicity, assuming BB is fast enough or we stall here
        
        when (blackbox.io.data_in_ready) {
           dma_cnt := dma_cnt + 1.U
           pending_load := false.B
           when (dma_cnt === (m_reg * n_reg) - 1.U) {
             state := State.sLoadB
             dma_cnt := 0.U
           }
        }
      }
    }
    
    is (State.sLoadB) {
      val pending_load = RegInit(false.B)
      
      when (!pending_load) {
        io.mem.req.valid := true.B
        io.mem.req.bits.addr := b_ptr + (dma_cnt << 3)
        when (io.mem.req.fire) {
          pending_load := true.B
        }
      }
      
      when (io.mem.resp.valid) {
        blackbox.io.data_in := io.mem.resp.bits.data
        blackbox.io.data_in_valid := true.B
        
        when (blackbox.io.data_in_ready) {
           dma_cnt := dma_cnt + 1.U
           pending_load := false.B
           when (dma_cnt === m_reg - 1.U) {
             state := State.sCompute
           }
        }
      }
    }
    
    is (State.sCompute) {
      // Wait for BB done
      when (blackbox.io.done) {
        state := State.sStoreRes
        dma_cnt := 0.U
      }
    }
    
    is (State.sStoreRes) {
      // Read from BB, write to Memory (b_ptr)
      blackbox.io.data_out_ready := true.B
      
      val pending_store = RegInit(false.B)
      val store_data = Reg(UInt(64.W))
      
      when (!pending_store && blackbox.io.data_out_valid) {
        store_data := blackbox.io.data_out
        pending_store := true.B
      }
      
      when (pending_store) {
        val stgen = new freechips.rocketchip.rocket.StoreGen(log2Ceil(8).U, b_ptr + (dma_cnt << 3), store_data, coreDataBytes)
        io.mem.req.bits.data := stgen.data
        io.mem.req.bits.mask := stgen.mask

        io.mem.req.valid := true.B
        io.mem.req.bits.cmd := M_XWR
        io.mem.req.bits.addr := b_ptr + (dma_cnt << 3)
        
        when (io.mem.req.fire) {
          dma_cnt := dma_cnt + 1.U
          pending_store := false.B
          when (dma_cnt === n_reg - 1.U) { // Result size is n (or m? usually x is size n)
             state := State.sDone
          }
        }
      }
    }
    
    is (State.sDone) {
      busy := false.B
      when (io.resp.fire) {
        state := State.sIdle
      }
    }
  }
}
