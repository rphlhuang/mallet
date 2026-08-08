"""Helpers for reducing one property's per-engine cells to a single verdict.

  Stage 1 -- CONSISTENCY: Any logically incompatible pair of engine
             answers is a CONFLICT and squashes all other results.

  Stage 2 -- ASSURANCE JOIN (only if CONSISTENCY satisied). 
             Strength order: UNKNOWN < BOUNDED-PASS(k) < PROVEN
             Can be overidden by: VACUOUS, REFUTED

Possible display strings: PROVEN, NOCEX, REFUTED, CONFLICT, VACUOUS, ASSUMED, UNKNOWN, ERROR.
"""

from __future__ import annotations

from model import Cell, EmissionStatus, Outcome, Property, Summary

PROVEN = "PROVEN"
NOCEX = "NOCEX"
REFUTED = "REFUTED"
CONFLICT = "CONFLICT"
VACUOUS = "VACUOUS"
ASSUMED = "ASSUMED"
UNKNOWN = "UNKNOWN"
ERROR = "ERROR"

# execute priority ordering from docstring
def priority_filter(cells: list[Cell]) -> str:
    order = {Outcome.PROVEN: 0, Outcome.CEX: 1, Outcome.NOCEX: 2,
             Outcome.TIMEOUT: 3, Outcome.ERROR: 4, Outcome.NOT_INSTALLED: 5}
    shown = [c for c in cells if c.outcome is not Outcome.NOT_INSTALLED]
    shown.sort(key=lambda c: order.get(c.outcome, 9))
    return "; ".join(f"{c.engine} {c}" for c in shown)

# adjudicate one property, given EmissionStatus, reachability, and Cells
def reduce(prop: Property) -> Summary:

    # assumptions: not proof obligations so no Cells
    if prop.is_assume:
        if prop.emission is EmissionStatus.CLEAN:
            return Summary(ASSUMED, detail="environment constraint (rely-guarantee)")
        return Summary(VACUOUS, detail="assumption folded away")

    # if non-clean emission, return
    if prop.emission is EmissionStatus.FOLDED:
        return Summary(VACUOUS, detail="folded to a constant (syntactic vacuity)")
    if prop.emission is not EmissionStatus.CLEAN:
        return Summary(ERROR, detail=prop.emission.value)

    cells = prop.cells
    provenance = priority_filter(cells)

    proven = [c for c in cells if c.outcome is Outcome.PROVEN]
    cexs = [c for c in cells if c.outcome is Outcome.CEX]
    nocex = [c for c in cells if c.outcome is Outcome.NOCEX]

    # Stage 1 -- CONSISTENCY
    if cexs and proven:
        return Summary(CONFLICT, provenance, disagreement=True,
                       detail="one engine found a CEX another proved impossible")
    for cx in cexs:
        d = cx.depth
        for nc in nocex:
            k = nc.depth
            if d is not None and k is not None and d <= k:
                return Summary(CONFLICT, provenance, disagreement=True,
                               detail=f"CEX@{d} vs {nc.engine} NOCEX-{k} (d<=k)")

    # Stage 2 -- ASSURANCE JOIN
    # VACUOUS overrides
    if prop.reach == "no" and not cexs:
        return Summary(VACUOUS, provenance, detail="antecedent unreachable (REACH=no)")

    # REFUTED overrides
    if cexs:
        d = min((c.depth for c in cexs if c.depth is not None), default=None)
        ks = [c.depth for c in nocex if c.depth is not None]
        detail = f"CEX@{d}" + (f" (bounded NOCEX only to {max(ks)})" if ks else "")
        return Summary(REFUTED, provenance, detail=detail)

    if proven:
        return Summary(PROVEN, provenance,
                       detail="holds for all time" + (f", corroborated by {len(nocex)} bounded pass" if nocex else ""))
    if nocex:
        k = max((c.depth for c in nocex if c.depth is not None), default=None)
        return Summary(NOCEX, provenance, detail=f"no CEX within {k} cycles (bounded, not a proof)")

    # something went really wrong here...
    return Summary(UNKNOWN, provenance, detail="no engine returned a usable verdict")