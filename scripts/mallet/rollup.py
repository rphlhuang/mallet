# Roll a set of verdicts up into one contract verdict
# For AXI for now

from __future__ import annotations

PRECEDENCE = (
    "CONFLICT", "REFUTED", "ERROR", "FAIL", "UNKNOWN",
    "NOCEX", "PROVEN", "VACUOUS", "FOLDED", "ASSUMED",
)
_RANK = {v: i for i, v in enumerate(PRECEDENCE)}


def is_axi(name: str) -> bool:
    return name.startswith("axi_")


def rollup(verdicts) -> str | None:
    present = [v for v in verdicts if v]
    if not present:
        return None
    return min(present, key=lambda v: _RANK.get(v, -1))
