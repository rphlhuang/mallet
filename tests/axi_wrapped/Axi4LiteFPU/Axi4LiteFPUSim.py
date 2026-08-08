import math, struct, logging, random
import cocotb
from axi_test_bridge.cocotb_bridge import COCOTB_Bridge

# Golden reference: pure-Python doubles rounded to IEEE-754 binary32 round-nearest-even
# canonical NaN result becomes 0x7FC00000, which is what the hardfloat/RISCV NaN produce

# bit <-> float helpers
def int_to_float(bits): # raw 32 bits -> double
    return struct.unpack("<f", struct.pack("<I", bits & 0xFFFFFFFF))[0]

def float_to_raw_int(x): # double to non-canonicalized raw bits; use for stimuli
    try:
        return struct.unpack("<I", struct.pack("<f", x))[0]
    except OverflowError:
        return NEG_INF if x < 0 else POS_INF

def canonicalize(x): # double to canon raw 32 bits; use for expected results
    return 0x7FC00000 if math.isnan(x) else float_to_raw_int(x)

# f32 nums   s/eemmmmmm
POS_ZERO   = 0x00000000
NEG_ZERO   = 0x80000000
POS_INF    = 0x7f800000
NEG_INF    = 0xff800000
QNAN       = 0x7fc00000 # quiet (mantissa MSB set)
SNAN       = 0x7f800001 # signaling (mantissa MSB clear)
MIN_SUBNRM = 0x00000001 # smallest positive subnormal
MAX_SUBNRM = 0x007fffff # largest subnormal
MIN_NORMAL = 0x00800000 # smallest positive normal
TWO_TO_24  = float_to_raw_int(16777216.0) # 2^24 -> incs of 2.0 here, so tests round-to-even

# helpers: Python raises on inf/nan, check manually
def ieee_div(x, y): # nan/0 and 0/0 -> nan
    if y == 0.0:
        if math.isnan(x) or x == 0.0:
            return float("nan")
        return math.copysign(float("inf"), math.copysign(1.0, x) * math.copysign(1.0, y))
    return x / y

def ieee_sqrt(x): # sqrt of negative (including -inf) -> nan
    if math.isnan(x) or x < 0.0:
        return float("nan")                                        
    return math.sqrt(x)

def fp32_ref(op, a_bits, b_bits):
    a = int_to_float(a_bits)
    b = int_to_float(b_bits)
    if op == "ADD": return canonicalize(a + b)
    if op == "SUB": return canonicalize(a - b)
    if op == "MUL": return canonicalize(a * b)
    if op == "DIV": return canonicalize(ieee_div(a, b))
    if op == "SQRT": return canonicalize(ieee_sqrt(a))
    raise ValueError(f"unknown op {op}")


VECTORS = [
    ("+0 , +0",                     POS_ZERO,   POS_ZERO),
    ("+0 , -0",                     POS_ZERO,   NEG_ZERO),
    ("-0 , -0",                     NEG_ZERO,   NEG_ZERO),
    ("+inf , 1.0",                  POS_INF,    float_to_raw_int(1.0)),
    ("+inf , -inf",                 POS_INF,    NEG_INF),
    ("1.0 , qNaN",                  float_to_raw_int(1.0), QNAN),
    ("1.0 , sNaN",                  float_to_raw_int(1.0), SNAN),
    ("maxSubnormal , minNormal",    MAX_SUBNRM, MIN_NORMAL),
    ("minSubnormal , minSubnormal", MIN_SUBNRM, MIN_SUBNRM),
    ("2^24 , 1.0 (tie-to-even)",    TWO_TO_24,  float_to_raw_int(1.0)),
    ("-1.0 , -2.0",                 float_to_raw_int(-1.0), float_to_raw_int(-2.0)),
    ("pi , e",                      float_to_raw_int(math.pi), float_to_raw_int(math.e)),
]

OPS = ["ADD", "SUB", "MUL", "DIV", "SQRT"]

MAX_POLLS = 1000

async def submit_and_pop(dut, tag, a_bits, b_bits, op):
    # write all fields, then submit via writing opcode
    opcode = getattr(dut.p.opcodes, op) # from opcodes written to JSON, recovered by cocotb_bridge
    await dut.writeWord(dut.p.tag_w, tag)
    await dut.writeWord(dut.p.a_w, a_bits)
    await dut.writeWord(dut.p.b_w, b_bits)
    await dut.writeWord(dut.p.opcode_w, opcode)

    # await q
    polls = 0
    while (await dut.readWord(dut.p.outq_cnt_r)) < 1:
        polls += 1
        assert polls <= MAX_POLLS, f"outq_cnt_r never went to 1 after {MAX_POLLS} polls"

    # pop completionQ head into result regs
    await dut.writeWord(dut.p.outq_pop_w, 0) 
    result = await dut.readWord(dut.p.result_r)
    got_tag = await dut.readWord(dut.p.tag_r)
    return result, got_tag


@cocotb.test()
async def test_single_flit(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()

    a_bits = float_to_raw_int(math.pi)
    b_bits = float_to_raw_int(math.e)

    output_buf = []
    for tag, op in enumerate(OPS):
        expected = fp32_ref(op, a_bits, b_bits)
        got, got_tag = await submit_and_pop(dut, tag, a_bits, b_bits, op)
        assert got == expected, f"{op}(pi, e): expected {expected:08x}, got {got:08x}"
        assert got_tag == tag, f"{op}(pi, e): expected tag {tag}, got {got_tag}"
        output_buf.append(f"{op}(pi, e): expected {int_to_float(expected)}, got {int_to_float(got)}")

    print(*output_buf, sep='\n')


@cocotb.test()
async def test_directed_vectors(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()
    logging.getLogger(f"cocotb.{cocotb_dut._name}.S_AXI").setLevel(logging.WARNING)

    tag = 0
    for op in OPS:
        for label, a_bits, b_bits in VECTORS:
            expected = fp32_ref(op, a_bits, b_bits)
            got, got_tag = await submit_and_pop(dut, tag, a_bits, b_bits, op)
            assert got == expected, f"{op}({label}): expected {expected:08x}, got {got:08x}"
            assert got_tag == tag, f"{op}({label}): expected tag {tag}, got {got_tag}"
            tag = (tag + 1) & 0xFF


@cocotb.test()
async def test_fuzz_random(cocotb_dut):
    dut = COCOTB_Bridge(cocotb_dut)
    await dut.setup()
    logging.getLogger(f"cocotb.{cocotb_dut._name}.S_AXI").setLevel(logging.WARNING)
    
    tag = 0
    for op in OPS:
        for _ in range(50):
            a_bits = random.getrandbits(32)
            b_bits = random.getrandbits(32)
            expected = fp32_ref(op, a_bits, b_bits)
            got, got_tag = await submit_and_pop(dut, tag, a_bits, b_bits, op)
            assert got == expected, f"{op}({a_bits}, {b_bits}): expected {expected:08x}, got {got:08x}"
            assert got_tag == tag, f"{op}({a_bits}, {b_bits}): expected tag {tag}, got {got_tag}"
            tag = (tag + 1) & 0xFF