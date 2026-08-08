"""Generators for two reports:

- generated/mallet-report.log for human readability
- generated/mallet-matrix.jsonl, one row per (property x engine x mode x config)
"""

from __future__ import annotations

import json
from datetime import datetime

from model import Cell, ModuleResult, Outcome

REPORT_LOG = "generated/mallet-report.log"
MATRIX_JSONL = "generated/mallet-matrix.jsonl"

# print helpers
_CELL_SHORT = {
    Outcome.NOT_INSTALLED: "n/i",
    Outcome.TIMEOUT: "TIMEOUT",
    Outcome.ERROR: "ERR",
    Outcome.PROVEN: "PROVEN",
}
def cell_str(c: Cell) -> str:
    if c.outcome in _CELL_SHORT:
        return _CELL_SHORT[c.outcome]
    return str(c)  # NOCEX-k / CEX@d
def tier(name: str) -> str:
    """Tier-1 reusable protocol contract vs Tier-2 design-specific hand property."""
    return "contract" if name.startswith("axi_") else "design"


# ----- HUMAN REPORT -----

def render_human(results: list[ModuleResult], meta: dict, engine_names: list[str]) -> str:
    out: list[str] = []
    ts = meta["timestamp"]
    out.append(f"================ mallet report @ {ts} ================")
    out.append(f"chisel {meta['chisel']} | {meta['firtool']} | btormc {meta['btormc']} | "
               f"engines={','.join(engine_names) or 'none'} | kmax={meta['kmax']} | "
               f"timeout={meta['timeout']}s | layers={meta['layers']}")
    out.append("-" * 79)

    ecols = "".join(f"{e:<9}" for e in engine_names)
    header = f"  {'MODULE':<18} {'PROPERTY':<22} {'PAST':>4} {'RCH':>3}  {ecols}{'VERDICT':<9} ENGLISH"
    out.append(header)

    n = dict(PROVEN=0, NOCEX=0, REFUTED=0, CONFLICT=0, VACUOUS=0, ASSUMED=0,
             UNKNOWN=0, ERROR=0)
    details: list[str] = []
    dut_total = 0

    for r in results:
        dut_total += r.n_dut_asserts
        if not r.emission_ok:
            out.append(f"  {r.module:<18} {'(module)':<22} {'-':>4} {'-':>3}  "
                       f"{'':<{9*len(engine_names)}}{'ERROR':<9} {r.emission_error}")
            n["ERROR"] += 1
            continue
        for p in r.props:
            s = r.summaries[p.label]
            n[s.verdict] = n.get(s.verdict, 0) + 1
            by_engine = {c.engine: c for c in p.cells}
            cells_str = "".join(
                f"{(cell_str(by_engine[e]) if e in by_engine else '-'):<9}"
                for e in engine_names
            )
            verdict = s.verdict + (" *" if s.disagreement else "")
            reach = p.reach if not p.is_assume else "-"
            out.append(
                f"  {r.module:<18} {p.name:<22} {p.max_past:>4} {reach:>3}  "
                f"{cells_str}{verdict:<9} {p.nl}"
            )
            if s.verdict in ("REFUTED", "CONFLICT"):
                tag = "CONFLICT" if s.disagreement else "refuted"
                line = f"--- {r.module} / {p.name}  [{tag}] {s.detail} ---\n    {s.provenance}"
                vcd = r.cex_vcd.get(p.label)
                if vcd:
                    line += f"\n    waveform: {vcd}  (open in GTKWave / Surfer)"
                details.append(line)

    out.append("-" * 79)
    out.append(
        f"  PROVEN={n['PROVEN']} NOCEX={n['NOCEX']} REFUTED={n['REFUTED']} "
        f"CONFLICT={n['CONFLICT']} VACUOUS={n['VACUOUS']} ASSUMED={n['ASSUMED']} "
        f"ERROR={n['ERROR']} | {dut_total} DUT asserts"
    )
    out.append("  PROVEN   = An unbounded proof was found.")
    out.append("  NOCEX    = No counterexample was found within kmax cycles; bounded so not a proof.")
    out.append("  REFUTED  = A counterexample exists, and all engines agree.")
    out.append("  CONFLICT = A counterexample exists, but engine(s) (marked *) found a CEX another ruled out.")
    out.append("  VACUOUS  = Proves nothing, so was simplified away or the antecedent of the implication was unreachable.")
    out.append("  ASSUMED  = an environment constraint for the DUT (rely-guarantee).")
    if details:
        out.append("")
        out.extend(details)
    out.append("")
    return "\n".join(out)

def append_human(text: str, path: str = REPORT_LOG) -> None:
    with open(path, "a") as f:
        f.write(text + "\n")

# ----- Machine report ------

def _base_row(meta: dict, r: ModuleResult, p, s) -> dict:
    return {
        "run": meta["timestamp"],
        "chisel": meta["chisel"],
        "firtool": meta["firtool"],
        "btormc": meta["btormc"],
        "kmax": meta["kmax"],
        "timeout": meta["timeout"],
        "module": r.module,
        "property": p.name,
        "label": p.label,
        "shape": p.shape,
        "tier": tier(p.name),
        "kind": p.kind,
        "max_past": p.max_past,
        "emission_status": p.emission.value,
        "reach": p.reach,
        "summary_verdict": s.verdict,
        "disagreement": s.disagreement,
        "provenance": s.provenance,
    }

def append_jsonl(results: list[ModuleResult], meta: dict, path: str = MATRIX_JSONL) -> int:
    rows: list[dict] = []
    for r in results:
        if not r.emission_ok:
            continue
        for p in r.props:
            s = r.summaries[p.label]
            if p.cells:
                for c in p.cells:
                    row = _base_row(meta, r, p, s)
                    row.update(engine=c.engine, mode=c.mode, outcome=c.outcome.value,
                               depth=c.depth, wallclock=round(c.wallclock, 3))
                    rows.append(row)
            else:
                row = _base_row(meta, r, p, s)
                row.update(engine=None, mode=None, outcome=None, depth=None, wallclock=None)
                rows.append(row)
    with open(path, "a") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")
    return len(rows)

def now_stamp() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")