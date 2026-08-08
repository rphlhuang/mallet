import random, logging
import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

@cocotb.test()
async def test_single_flit(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()

    a = 3
    b = 4
    await dut.writeWord(dut.p.a_w, a)
    await dut.writeWord(dut.p.b_w, b)
    await dut.writeWord(dut.p.push_w, 1)
    
    expected = a * b
    while True:
        status = await dut.readWord(dut.p.status_r)
        if status == 1:
            break
    
    got = await dut.readWord(dut.p.result_r)
    assert (got == expected), f"With a={a}, b={b}: expected {expected:08x}, got {got:08x}"

@cocotb.test()
async def test_fuzz(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()
    logging.getLogger(f"cocotb.{cocotb_dut._name}.S_AXI").setLevel(logging.WARNING)

    NUM_TESTS = 10
    MAX_CYCLES = 200
    for _ in range(NUM_TESTS):
        num_cycles = random.randint(0, MAX_CYCLES)
        expected = 0
        for _ in range(num_cycles - 1):
            a = random.randint(0, (1 << 7))
            b = random.randint(0, (1 << 7))
            await dut.writeWord(dut.p.a_w, a)
            await dut.writeWord(dut.p.b_w, b)
            await dut.writeWord(dut.p.push_w, 0)
            expected += a * b

        a = random.randint(0, (1 << 7) - 1)
        b = random.randint(0, (1 << 7) - 1)
        await dut.writeWord(dut.p.a_w, a)
        await dut.writeWord(dut.p.b_w, b)
        await dut.writeWord(dut.p.push_w, 1)
        expected += a * b
        
        while True:
            status = await dut.readWord(dut.p.status_r)
            if status == 1:
                break
        
        got = await dut.readWord(dut.p.result_r)
        assert(got == expected), f"With a={a}, b={b}: expected {expected:08x}, got {got:08x}"