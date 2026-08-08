#!/usr/bin/env python3
"""mallet's entry point.

For every DUT (a *.fir that generates props.json):
    emit btor2 once -> classify emission -> reachability pass ->
    slice per property -> run thread for each (property x engine) ->
    reduce each property's cells via lattice.py -> human report + JSONLines

Usage: run.py [--kmax 20] [--timeout 120] [--workers N] [--no-gate] <fir-glob>...
"""

from __future__ import annotations

import argparse
import glob as globmod
import json
import os
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import emit
import lattice
import report
from engines import ALL_ENGINES, installed_engines
from model import Cell, ModuleResult, Outcome, Property

_BLINE = re.compile(r"^b(\d+)")

# helper for generating metadata, TODO: add pono and rIC3 versions
def versions(kmax: int, timeout: float) -> dict:
    def run_out(cmd: list[str]) -> str:
        try:
            return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=10).stdout
        except Exception:
            return ""

    def first_line(cmd: list[str]) -> str:
        out = run_out(cmd).splitlines()
        return out[0].strip() if out else "?"
    chisel = "?"
    try:
        for line in open("build.sbt"):
            if "chiselVersion" in line:
                m = re.search(r'"([0-9][0-9.]*)"', line)
                if m:
                    chisel = m.group(1)
                break
    except OSError:
        pass
    ftm = re.search(r"firtool-[0-9.]+", run_out(["firtool", "--version"]))
    return {
        "timestamp": report.now_stamp(),
        "chisel": chisel,
        "firtool": ftm.group(0) if ftm else "firtool?",
        "btormc": first_line(["btormc", "--version"]),
        "kmax": kmax,
        "timeout": timeout,
        "layers": emit.LAYERS,
    }

# run btormc on a whole model and return the violated bad indices
def btormc_violated(btor2: str, kmax: int, timeout: float) -> list[int]:
    mc = shutil.which("btormc")
    if not mc:
        return []
    try:
        out = subprocess.run([mc, "-kmax", str(kmax), "--stop-first", "0", btor2],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return []
    idxs = set()
    for ln in out.splitlines():
        m = _BLINE.match(ln.strip())
        if m:
            idxs.add(int(m.group(1)))
    return sorted(idxs)

# create reachability model and return reachable antecedents
def compute_reach(reach_fir: str, base: str, kmax: int, timeout: float) -> set[str] | None:
    if not Path(reach_fir).exists():
        return None
    rb = base + ".reach"
    emit.run_firtool(reach_fir, rb + ".btor2", rb + ".mlir")
    ok, err, rasserts, rassumes = emit.classify_module(rb + ".btor2", rb + ".mlir") # sanity check emits
    if not ok:
        return None
    covers: set[str] = set()
    for bi in btormc_violated(rb + ".btor2", kmax, timeout):
        if bi < len(rasserts) and rasserts[bi].startswith("COVER_"):
            covers.add(rasserts[bi])
    return covers

# replay btormc CEX using btorsim --> outputs VCD
def reproduce_cex(slice_path: str, base_label: str, kmax: int, timeout: float) -> str:
    mc, sim = shutil.which("btormc"), shutil.which("btorsim")
    if not (mc and sim):
        return ""
    try:
        wit = subprocess.run([mc, "-kmax", str(kmax), "--stop-first", "0", slice_path],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""
    if not any(_BLINE.match(l.strip()) for l in wit.splitlines()):
        return ""
    witp, vcd = base_label + ".cex.wit", base_label + ".cex.vcd"
    Path(witp).write_text(wit)
    try:
        rc = subprocess.run([sim, slice_path, witp, "--vcd", vcd, "--hierarchical-symbols"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            timeout=timeout).returncode
    except subprocess.TimeoutExpired:
        return ""
    return vcd if rc == 0 else ""

# create main model
def process_fir(fir: str, kmax: int, timeout: float, workers: int) -> ModuleResult | None:
    # setup paths
    name = Path(fir).stem
    dir_ = Path(fir).parent.parent # generated/<pkg>/chirrtl/x.fir -> generated/<pkg>
    sidecar = dir_ / "props" / f"{name}.props.json"
    if not sidecar.exists():
        return None # no mallet-worthy annotations exist.
    base = str(dir_ / "btor2" / name)
    Path(base).parent.mkdir(parents=True, exist_ok=True)
    emit.run_firtool(fir, base + ".btor2", base + ".final.mlir")

    # generate model
    ok, err, asserts, assumes = emit.classify_module(base + ".btor2", base + ".final.mlir")
    props = [Property.from_json(p) for p in json.load(open(sidecar))["props"]]
    result = ModuleResult(module=name, fir=fir, btor2=base + ".btor2",
                          emission_ok=ok, emission_error=err, props=props)
    if not ok:
        return result

    result.n_dut_asserts = emit.assign_positions(props, asserts, assumes)

    # rechability pass
    covers = compute_reach(str(dir_ / "reach" / f"{name}.fir"), base, kmax, timeout)
    for p in props:
        if p.cover_label and covers is not None:
            p.reach = "yes" if p.cover_label in covers else "no"

    # slice model into one "bad" assert at a time
    # this prevents one fail from blocking others and parallelizes
    clean_asserts = [p for p in props
                     if not p.is_assume and p.emission.name == "CLEAN" and p.pos is not None]
    slices: dict[str, str] = {}
    for p in clean_asserts:
        sp = f"{base}.p{p.pos}.btor2"
        emit.slice_keep_bad(base + ".btor2", p.pos, sp)
        slices[p.label] = sp

    # build thread pool, populating the matrix
    cells: dict[tuple[str, str], Cell] = {}
    jobs = [(p, e) for p in clean_asserts for e in ALL_ENGINES]
    if jobs:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            fut = {pool.submit(e.run, slices[p.label], kmax, timeout): (p.label, e.name)
                   for p, e in jobs}
            for f in as_completed(fut):
                key = fut[f]
                cells[key] = f.result()
    for p in clean_asserts:
        p.cells = [cells[(p.label, e.name)] for e in ALL_ENGINES if (p.label, e.name) in cells]

    # reproduce counterexample VCDs, if they exist
    for p in props:
        s = lattice.reduce(p)
        result.summaries[p.label] = s
        if s.verdict in ("REFUTED", "CONFLICT") and p.label in slices:
            vcd = reproduce_cex(slices[p.label], f"{base}.p{p.pos}", kmax, timeout)
            if vcd:
                result.cex_vcd[p.label] = vcd
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description="mallet engine")
    ap.add_argument("globs", nargs="*", default=["generated/*/chirrtl/*.fir"],
                    help="fir glob(s), change directory searched for *.fir files")
    ap.add_argument("--kmax", type=int, default=20, help="BMC max depth")
    ap.add_argument("--timeout", type=float, default=120.0, help="timeout, in secs")
    ap.add_argument("--workers", type=int, default=os.cpu_count() or 4)
    args = ap.parse_args()

    firs = sorted({f for g in args.globs for f in globmod.glob(g)})
    meta = versions(args.kmax, args.timeout)

    results: list[ModuleResult] = []
    for fir in firs:
        r = process_fir(fir, args.kmax, args.timeout, args.workers)
        if r is not None:
            results.append(r)

    engine_names = [e.name for e in ALL_ENGINES]
    text = report.render_human(results, meta, engine_names)
    print(text)
    report.append_human(text)
    nrows = report.append_jsonl(results, meta)
    print(f"  (report appended to {report.REPORT_LOG}; {nrows} rows -> {report.MATRIX_JSONL})")

    bad = any(
        (not r.emission_ok) or any(s.verdict in ("REFUTED", "CONFLICT")
                                   for s in r.summaries.values())
        for r in results
    )
    return bad


if __name__ == "__main__":
    sys.exit(main())
