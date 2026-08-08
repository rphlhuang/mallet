import random

import cocotb
from cocotb.clock import Clock
from cocotb.queue import Queue
from cocotb.triggers import ClockCycles, ReadOnly, RisingEdge, FallingEdge
 
SEED = 42
WIDTH = 8
MAX = 1 << WIDTH
NUM_MSGS = 200
GROUP_SIZE_POOL = [1, 1, 2, 2, 3, 4, 8]
DRIVER_DELAY_POOL = [0, 0, 0, 0,1, 2, 4, 7]
TIMEOUT_CYCLES = 1000
CLOCK_PERIOD = 20

async def reset(dut):
    dut.reset.value = 1
    dut.io_in_valid.value = 0
    dut.io_in_bits_a.value = 0
    dut.io_in_bits_b.value = 0
    dut.io_in_bits_last.value = 0
    dut.io_out_ready.value = 0
    await ClockCycles(dut.clock, 5)
    await FallingEdge(dut.clock)
    dut.reset.value = 0
    await FallingEdge(dut.clock)

def make_groups(rng, num_msgs):
    groups = []
    remaining = num_msgs
    while remaining > 0:
        groups.append(min(rng.choice(GROUP_SIZE_POOL), remaining))
        remaining -= groups[-1]
    return groups

async def driver(dut, rng, groups, always_valid=False):
    for size in groups:
        for i in range(size):
            # delay semi-random amount
            if not always_valid:
                for _ in range(rng.choice(DRIVER_DELAY_POOL)):
                    await RisingEdge(dut.clock)
                    dut.io_in_valid.value = 0

            # drive random operands (and last when finished) and valid high
            await RisingEdge(dut.clock)
            dut.io_in_valid.value = 1
            dut.io_in_bits_a.value = rng.randint(0, MAX - 1)
            dut.io_in_bits_b.value = rng.randint(0, MAX - 1)
            dut.io_in_bits_last.value = 1 if (i == size - 1) else 0

            # wait until handshake (ready_o), then repeat
            cycles_until_timeout = TIMEOUT_CYCLES
            while True:
                assert (cycles_until_timeout != 0), "Timed out while waiting for io_in_ready."
                await ReadOnly()
                if (dut.io_in_ready.value == 1):
                    break
                await RisingEdge(dut.clock)
                cycles_until_timeout -= 1

    # deassert when done!
    await RisingEdge(dut.clock)
    dut.io_in_valid.value = 0

async def receiver(dut, rng, always_ready=False):
    # drive io_out_ready with random probability
    while True:
        await RisingEdge(dut.clock)
        dut.io_out_ready.value = 1 if ((rng.random() > 0.7) or always_ready) else 0

async def monitor_in(dut, q):
    while True:
        await FallingEdge(dut.clock)
        await ReadOnly()
        if (dut.io_in_ready.value == 1 and dut.io_in_valid.value == 1):
            q.put_nowait((int(dut.io_in_bits_a.value), int(dut.io_in_bits_b.value), int(dut.io_in_bits_last.value)))

async def monitor_out(dut, q):
    while True:
        await FallingEdge(dut.clock)
        await ReadOnly()
        if (dut.io_out_ready.value == 1 and dut.io_out_valid.value == 1):
            q.put_nowait(int(dut.io_out_bits.value))

async def scoreboard(in_q, expected_q, num_groups):
    for i in range(num_groups):
        acc = 0
        while True:
            a, b, last = await in_q.get()
            acc += a * b
            if last:
                break
        expected_q.put_nowait(acc)

@cocotb.test()
async def test_single_transfer(dut):
    Clock(dut.clock, CLOCK_PERIOD, unit="ns").start()
    await reset(dut)

    await RisingEdge(dut.clock)
    dut.io_in_valid.value = 1
    dut.io_in_bits_a.value = 0x0F
    dut.io_in_bits_b.value = 0x11
    dut.io_in_bits_last.value = 1

    await RisingEdge(dut.clock)
    dut.io_in_valid.value = 0
    dut.io_in_bits_last.value = 0

    await ClockCycles(dut.clock, 5)
    await RisingEdge(dut.clock)
    dut.io_out_ready.value = 1

    await FallingEdge(dut.clock)
    got = dut.io_out_bits.value
    expected = 0x0F * 0x11
    assert (got == expected), f"Single cycle smoke test: got {got} but expected {expected}"


@cocotb.test()
async def test_fuzz(dut):
    rng = random.Random(SEED)
    Clock(dut.clock, CLOCK_PERIOD, unit="ns").start()
    await reset(dut)

    groups = make_groups(rng, NUM_MSGS)

    sent_q, received_q, expected_q = Queue(), Queue(), Queue()
    cocotb.start_soon(monitor_in(dut, sent_q))
    cocotb.start_soon(monitor_out(dut, received_q))
    cocotb.start_soon(scoreboard(sent_q, expected_q, len(groups)))
    cocotb.start_soon(receiver(dut, rng))
    driver_out = cocotb.start_soon(driver(dut, rng, groups))

    for i in range(len(groups)):
        expected = await expected_q.get()
        got = await received_q.get()
        # dut._log.info(f"On group #{i}, remaining expected_q contents: {list(expected_q._queue)}")
        # dut._log.info(f"On group #{i}, remaining received_q contents: {list(received_q._queue)}")
        assert got == expected, f"Group #{i}: expected 0x{expected:04x}, got 0x{got:04x}."

    await driver_out
    await ClockCycles(dut.clock, 10)

    assert received_q.empty(), "DUT produced more outputs than were expected."
    dut._log.info(f"{len(groups)} dot-product groups ({NUM_MSGS} beats) passed!")


@cocotb.test()
async def test_max_throughput(dut):
    rng = random.Random(SEED)
    Clock(dut.clock, CLOCK_PERIOD, unit="ns").start()
    await reset(dut)

    groups = make_groups(rng, NUM_MSGS)

    t_start = cocotb.utils.get_sim_time(unit='ns')

    sent_q, received_q, expected_q = Queue(), Queue(), Queue()
    cocotb.start_soon(monitor_in(dut, sent_q))
    cocotb.start_soon(monitor_out(dut, received_q))
    cocotb.start_soon(scoreboard(sent_q, expected_q, len(groups)))
    cocotb.start_soon(receiver(dut, rng, always_ready=True))
    driver_out = cocotb.start_soon(driver(dut, rng, groups, always_valid=True))

    for i in range(len(groups)):
        expected = await expected_q.get()
        got = await received_q.get()
        assert got == expected, f"Group #{i}: expected 0x{expected:04x}, got 0x{got:04x}."

    await driver_out
    t_end = cocotb.utils.get_sim_time(unit='ns')
    await ClockCycles(dut.clock, 10)
    assert received_q.empty(), "DUT produced more outputs than were expected."

    num_cycles = (t_end - t_start) / CLOCK_PERIOD
    expected_cycles = 2 * NUM_MSGS + len(groups)
    assert (num_cycles == expected_cycles), f"Max throughput must take {expected_cycles} cycles, took {num_cycles} instead."
    dut._log.info(f"Max throughput test passed: took {num_cycles} cycles for {len(groups)} groups ({NUM_MSGS} beats).")