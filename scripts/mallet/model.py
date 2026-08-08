"""Shared datatypes.

The pipeline is: emit btor2 once -> classify emission -> slice per property ->
run every (property x engine) job -> reduce each property's cells to one summary.

EmissionStatus is what happened to the property as it gets lowered to the formats that get sent to the engines.
Outcome is what the engine passed back to us, and lives in a Cell.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


# status of each property's lowering
class EmissionStatus(str, Enum):
    CLEAN = "clean"                 # valid btor2 emitted, label present in IR
    SENTINEL = "sentinel"           # got 18446744073709551615 which means an unlowerable LTL op
    RESIDUAL_LTL = "residual-ltl"   # an ltl.* op survived lowering into the IR
    FOLDED = "folded"               # label not in IR: compiler simplified it away (often syntactic vacuity, e.g. x === x)
    NO_ELABORATE = "no-elaborate"   # firtool emitted no btor2 at all
    UNMATCHED = "unmatched"         # num_labels != num_bad, a fatal error: refuse to attribute any verdict

NON_CLEAN = frozenset(
    s for s in EmissionStatus if s is not EmissionStatus.CLEAN
)


# results of one enginer on one property
class Outcome(str, Enum):
    PROVEN = "PROVEN"               # unbounded UNSAT / IC3 fixpoint
    NOCEX = "NOCEX"                 # bounded: no counterexample up to depth kmax
    CEX = "CEX"                     # counterexample found before kmax
    TIMEOUT = "TIMEOUT"
    NOT_INSTALLED = "NOT-INSTALLED"
    ERROR = "ERROR"                 # engine crashed or unparseable output

@dataclass # one (property x engine) matrx cell
class Cell:
    engine: str
    mode: str
    outcome: Outcome
    depth: Optional[int] = None     # d for CEX (trace length), k if NOCEX
    wallclock: float = 0.0          # seconds
    raw: str = ""                   # engine stdout

    def __str__(self) -> str:
        if self.outcome is Outcome.NOCEX and self.depth is not None:
            return f"NOCEX-{self.depth}"
        if self.outcome is Outcome.CEX and self.depth is not None:
            return f"CEX@{self.depth}"
        return self.outcome.value

@dataclass # one mallet property, loaded from props.json and filled out as it moves through the pipeline
class Property:

    # from props.json
    label: str                      # unique IR label, e.g. MT_04_axi_b_valid_stable_...
    name: str                       # human name, e.g. axi_b_valid_stable
    idx: int
    kind: str                       # "assert" or "assume"
    shape: str                      # template: "implies" or "always" or ...
    max_past: int
    nl: str
    cover_label: Optional[str]      # COVER_... for reachability, or None
    note: str = ""

    # filled throughout pipeline
    pos: Optional[int] = None # index among ordered clocked_asserts
    emission: EmissionStatus = EmissionStatus.CLEAN
    reach: str = "?" # "yes" or "no" or "?"
    cells: list[Cell] = field(default_factory=list)

    @property
    def is_assume(self) -> bool:
        return self.kind == "assume"

    @staticmethod
    def from_json(d: dict) -> "Property":
        return Property(
            label=d["label"],
            name=d["name"],
            idx=d.get("idx", -1),
            kind=d.get("kind", "assert"),
            shape=d.get("shape", ""),
            max_past=d.get("maxPast", 0),
            nl=d.get("nl", ""),
            cover_label=d.get("coverLabel") or None,
            note=d.get("note", ""),
        )

@dataclass # collapsing all of a Property's Cells to a final verdict
class Summary:
    verdict: str
    provenance: str = ""            # e.g. "rIC3 PROVEN; btormc NOCEX-20"
    disagreement: bool = False
    detail: str = ""                # extra note, e.g. "CEX@3 vs NOCEX-20"

@dataclass # results of one Chisel module's assert/assume set
class ModuleResult:
    module: str
    fir: str
    btor2: str
    emission_ok: bool # False means module-level ERROR, e.g. UNMATCHED
    emission_error: str = ""
    props: list[Property] = field(default_factory=list)
    summaries: dict[str, Summary] = field(default_factory=dict) # keys are property labels
    n_dut_asserts: int = 0                                      # unlabelled DUT asserts
    cex_vcd: dict[str, str] = field(default_factory=dict)       # label -> vcd path