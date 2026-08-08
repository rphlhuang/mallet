"""Backend engine helpers.
For now:
  btormc  -kmax 20 --stop-first 0   bounded BMC        SAT->b<i> / else NOCEX-k
  rIC3    -e ic3 --witness          unbounded IC3/PDR  UNSAT->PROVEN, SAT->CEX
  pono    -e mbic3 --witness        unbounded IC3      unsat->PROVEN, sat->CEX
                                    (mbic3 doesn't require MathSAT, so we use for now)

TODO: Add AVR, and pono MathSAT
TODO: Add all checkers from each engine 
"""

from __future__ import annotations

import re
import shutil
import subprocess
import time

from model import Cell, Outcome

_FRAME = re.compile(r"@(\d+)")
_BLINE = re.compile(r"^b\d+")

# CEX depth is "@N" value in btor2 witness, if present
def max_frame(text: str) -> int | None:
    frames = [int(m) for m in _FRAME.findall(text)]
    return max(frames) if frames else None

# plug-and-play engine abstraction, implement command() and parse()
class Engine:

    name = ""
    mode = ""
    binary = ""

    def __init__(self) -> None:
        self.path = shutil.which(self.binary or self.name)

    def installed(self) -> bool:
        return self.path is not None

    def command(self, slice_path: str, kmax: int) -> list[str]:
        raise NotImplementedError

    def parse(self, out: str, kmax: int) -> tuple[Outcome, int | None]:
        raise NotImplementedError

    def run(self, slice_path: str, kmax: int, timeout: float) -> Cell:
        if not self.installed():
            return Cell(self.name, self.mode, Outcome.NOT_INSTALLED)
        t0 = time.monotonic()
        try:
            proc = subprocess.run(
                self.command(slice_path, kmax),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            return Cell(self.name, self.mode, Outcome.TIMEOUT,
                        wallclock=timeout, raw="(wall-clock timeout)")
        dt = time.monotonic() - t0
        out = proc.stdout or ""
        try:
            outcome, depth = self.parse(out, kmax)
        except Exception as e:  # prevent crash on unparseable output, give ERROR cell
            return Cell(self.name, self.mode, Outcome.ERROR, wallclock=dt,
                        raw=f"parse error: {e}")
        return Cell(self.name, self.mode, outcome, depth=depth, wallclock=dt,
                    raw=out.strip()[-200:])


class Btormc(Engine):
    name = "btormc"
    mode = "bmc"
    binary = "btormc"

    def command(self, slice_path: str, kmax: int) -> list[str]:
        return [self.path, "-kmax", str(kmax), "--stop-first", "0", slice_path]

    def parse(self, out: str, kmax: int) -> tuple[Outcome, int | None]:
        # btormc prints `b<i>` for every violated property, then a witness with @N frames
        # no `b` line within the bound => no counterexample <= kmax
        if any(_BLINE.match(ln.strip()) for ln in out.splitlines()):
            return Outcome.CEX, max_frame(out)
        return Outcome.NOCEX, kmax


class RIC3(Engine):
    name = "rIC3"
    mode = "ic3"
    binary = "rIC3"

    def command(self, slice_path: str, kmax: int) -> list[str]:
        # -e ic3 forces the IC3/PDR proving engine, --witness yields a trace on SAT
        return [self.path, "-e", "ic3", "--witness", slice_path]

    def parse(self, out: str, kmax: int) -> tuple[Outcome, int | None]:
        lines = {ln.strip() for ln in out.splitlines()}
        if "UNSAT" in lines:
            return Outcome.PROVEN, None        # unbounded proof
        if "SAT" in lines:
            return Outcome.CEX, max_frame(out) # refutation
        return Outcome.ERROR, None


class Pono(Engine):
    name = "pono"
    mode = "mbic3"
    binary = "pono"

    def command(self, slice_path: str, kmax: int) -> list[str]:
        return [self.path, "-e", "mbic3", "--witness", slice_path]

    def parse(self, out: str, kmax: int) -> tuple[Outcome, int | None]:
        lines = {ln.strip().lower() for ln in out.splitlines()}
        if "unsat" in lines:
            return Outcome.PROVEN, None
        if "sat" in lines:
            return Outcome.CEX, max_frame(out)
        return Outcome.ERROR, None


# class Avr(Engine):
#     name = "avr"
#     mode = "default"
#     binary = "avr"

#     def command(self, slice_path: str, kmax: int) -> list[str]:
#         return [self.path or "avr", slice_path]

#     def parse(self, out: str, kmax: int) -> tuple[Outcome, int | None]:
#         low = {ln.strip().lower() for ln in out.splitlines()}
#         if "unsat" in low or "safe" in low:
#             return Outcome.PROVEN, None
#         if "sat" in low or "unsafe" in low:
#             return Outcome.CEX, max_frame(out)
#         return Outcome.ERROR, None


# The canonical engine set for this week, in report-column order.
ALL_ENGINES: list[Engine] = [Btormc(), RIC3(), Pono()]


def installed_engines() -> list[Engine]:
    return [e for e in ALL_ENGINES if e.installed()]