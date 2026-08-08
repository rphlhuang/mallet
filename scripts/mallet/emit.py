"""Emit helpers that also decides whether a property is checkable in the first place."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

from model import EmissionStatus, Property

# firtool verification layers
LAYERS = "Verification,Verification.Assert,Verification.Assume"
# LTL ops from Chisel leave op id of 2^64-1 in the btor2 for some reason
SENTINEL = "18446744073709551615"
# useful regex helpers from Claude
_OP = re.compile(r"^\s*verif\.clocked_(assert|assume) %")
_LBL = re.compile(r'label "([A-Za-z0-9_]+)"')
_BAD = re.compile(r"^\d+ bad ")
_CONSTRAINT = re.compile(r"^\d+ constraint ")
def _count(path: str, rx: re.Pattern) -> int:
    with open(path, errors="replace") as f:
        return sum(1 for line in f if rx.match(line))

def run_firtool(fir: str, btor2_out: str, mlir_out: str) -> None:
    Path(btor2_out).parent.mkdir(parents=True, exist_ok=True)
    with open(btor2_out, "w") as bt, open(mlir_out, "w") as ml:
        subprocess.run(
            ["firtool", "--btor2", f"--enable-layers={LAYERS}", fir,
             "--mlir-print-ir-after=hw-flatten-modules"],
            stdout=bt, stderr=ml, check=False,
        )

# returns (assert_labels, assume_labels) in flattened-IR order
def ordered_labels(mlir_path: str) -> tuple[list[str], list[str]]:
    asserts: list[str] = []
    assumes: list[str] = []
    with open(mlir_path, errors="replace") as f:
        for line in f:
            m = _OP.search(line)
            if not m:
                continue
            lab = _LBL.search(line)
            name = lab.group(1) if lab else "-"
            (asserts if m.group(1) == "assert" else assumes).append(name)
    return asserts, assumes

# emits module, returning (ok, error, assert_labels, assume_labels)
# if ok=False, that means the whole module failed to compile
def classify_module(btor2_path: str, mlir_path: str) -> tuple[bool, str, list[str], list[str]]:
    p = Path(btor2_path)
    if not p.exists() or p.stat().st_size == 0:
        return False, "firtool emitted no BTOR2", [], []

    text = p.read_text(errors="replace")

    if SENTINEL in text: # don't let unlowerable ids through
        return False, "unlowered LTL op leaked (dangling operand id / |=>)", [], []

    asserts, assumes = ordered_labels(mlir_path)

    nbad = _count(btor2_path, _BAD)
    ncon = _count(btor2_path, _CONSTRAINT)
    if len(asserts) != nbad or len(assumes) != ncon:
        return (False,
                f"UNMATCHED {len(asserts)} assert/{nbad} bad, {len(assumes)} assume/{ncon} constraint",
                asserts, assumes)
    return True, "", asserts, assumes

# give each Property its btor2 position (so we can track it) and EmissionStatus
def assign_positions(props: list[Property], asserts: list[str], assumes: list[str]) -> int:
    apos = {lab: i for i, lab in enumerate(asserts) if lab != "-"}
    aset = set(l for l in assumes if l != "-")
    for p in props:
        if p.is_assume:
            # Present -> a live environment constraint; absent -> folded away.
            p.emission = EmissionStatus.CLEAN if p.label in aset else EmissionStatus.FOLDED
            continue
        pos = apos.get(p.label)
        if pos is None:
            p.emission = EmissionStatus.FOLDED
        else:
            p.pos = pos
            p.emission = EmissionStatus.CLEAN
    return sum(1 for lab in asserts if lab == "-")

# isolate just one property's btor2 position using regex
def slice_keep_bad(btor2_path: str, keep_pos: int, out_path: str) -> None:
    c = -1
    with open(btor2_path) as f, open(out_path, "w") as o:
        for line in f:
            if _BAD.match(line):
                c += 1
                if c == keep_pos:
                    o.write(line)
                # other bad lines: dropped
            else:
                o.write(line)