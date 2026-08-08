#!/usr/bin/env python3
# [Claude] HTML dashboard for generated/mallet-report.log and generated/mallet-matrix.jsonl.

from __future__ import annotations

import html
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT_LOG = ROOT / "generated" / "mallet-report.log"
MATRIX_JSONL = ROOT / "generated" / "mallet-matrix.jsonl"
OUT = ROOT / "generated" / "mallet-dashboard.html"

VERDICTS = ("PROVEN", "REFUTED", "CONFLICT", "VACUOUS", "ASSUMED",
            "ERROR", "NOCEX", "FAIL", "FOLDED", "UNKNOWN")
VERDICT_RE = re.compile(r"\s+(" + "|".join(VERDICTS) + r")( \*)?\s+")
RUN_HEADER_RE = re.compile(r"^================ mallet report @ (.+?) ================$", re.MULTILINE)


@dataclass
class Row:
    module: str
    prop: str
    past: str
    reach: str | None
    cells: list[tuple[str, str]] # (engine, cell text)
    verdict: str
    disagreement: bool
    english: str


@dataclass
class Run:
    ts: str
    config: str
    engine_names: list[str]
    modules: dict[str, list[Row]] = field(default_factory=dict)
    summary: str = ""
    details: dict[tuple[str, str], str] = field(default_factory=dict)


def is_dashes(line: str) -> bool:
    s = line.strip()
    return bool(s) and set(s) == {"-"}


def parse_row(line: str, engine_names: list[str]) -> Row | None:
    matches = list(VERDICT_RE.finditer(line))
    if not matches:
        return None
    m = matches[-1]
    pre = line[: m.start()].strip()
    verdict, star = m.group(1), m.group(2)
    english = line[m.end():].strip()
    tokens = pre.split()
    if len(tokens) < 3:
        return None
    module, prop, past = tokens[0], tokens[1], tokens[2]
    reach = None
    cells: list[tuple[str, str]] = []
    if engine_names:
        reach = tokens[3] if len(tokens) > 3 else "-"
        cells = list(zip(engine_names, tokens[4:]))
    return Row(module, prop, past, reach, cells, verdict, bool(star), english)


def parse_report_log(text: str) -> list[Run]:
    if not text.strip():
        return []
    starts = [m.start() for m in RUN_HEADER_RE.finditer(text)] + [len(text)]
    runs: list[Run] = []
    for a, b in zip(starts, starts[1:]):
        block = text[a:b].rstrip("\n").split("\n")
        ts = RUN_HEADER_RE.match(block[0]).group(1)
        config = block[1] if len(block) > 1 else ""
        em = re.search(r"engines=([\w,]*)", config)
        engine_names = [e for e in em.group(1).split(",") if e] if em else []
        run = Run(ts=ts, config=config, engine_names=engine_names)

        i = 2
        while i < len(block) and not is_dashes(block[i]):
            i += 1
        i += 2  # skip dash separator + column-header line
        while i < len(block) and not is_dashes(block[i]):
            row = parse_row(block[i], engine_names)
            if row is not None:
                run.modules.setdefault(row.module, []).append(row)
            i += 1
        i += 1  # skip closing dash separator
        if i < len(block):
            run.summary = block[i]
            i += 1
        while i < len(block) and not block[i].startswith("--- "):
            i += 1
        while i < len(block) and block[i].startswith("--- "):
            header = block[i]
            i += 1
            body: list[str] = []
            while i < len(block) and block[i].strip() != "" and not block[i].startswith("--- "):
                body.append(block[i])
                i += 1
            while i < len(block) and block[i].strip() == "":
                i += 1
            hm = re.match(r"^--- (\S+) / (\S+)\s+(.*?)\s*---$", header)
            if hm:
                run.details[(hm.group(1), hm.group(2))] = "\n".join(body)
        runs.append(run)
    return runs


def parse_wallclocks(path: Path) -> dict[tuple[str, str, str, str], float]:
    wc: dict[tuple[str, str, str, str], float] = {}
    if not path.exists():
        return wc
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if d.get("engine") and d.get("wallclock") is not None:
                wc[(d["run"], d["module"], d["property"], d["engine"])] = d["wallclock"]
    return wc


def summary_badges(summary: str) -> str:
    counts = re.findall(r"([A-Z]+)=(\d+)", summary)
    tail = summary.split("|", 1)[1].strip() if "|" in summary else ""
    badges = "".join(
        f'<span class="badge v-{k}">{k}={v}</span>' for k, v in counts if v != "0"
    )
    if tail:
        badges += f'<span class="badge-tail">{html.escape(tail)}</span>'
    return badges


def render_row(run: Run, row: Row, wc: dict) -> str:
    e = html.escape
    cells_html = ""
    if run.engine_names:
        cells_html += f'<td class="reach r-{e(row.reach or "-")}">{e(row.reach or "-")}</td>'
        cell_map = dict(row.cells)
        for engine in run.engine_names:
            val = cell_map.get(engine, "-")
            t = wc.get((run.ts, row.module, row.prop, engine))
            title = f' title="{engine}: {t:.3f}s"' if t is not None else ""
            cells_html += f'<td class="cell"{title}>{e(val)}</td>'
    verdict_cls = f"v-{row.verdict}" + (" disagree" if row.disagreement else "")
    verdict_text = row.verdict + (" *" if row.disagreement else "")
    detail = run.details.get((row.module, row.prop))
    detail_html = ""
    if detail:
        detail_html = f'<details><summary>trace</summary><pre>{e(detail)}</pre></details>'
    return (
        "<tr>"
        f'<td class="prop">{e(row.prop)}</td>'
        f'<td class="past">{e(row.past)}</td>'
        f"{cells_html}"
        f'<td class="verdict {verdict_cls}">{e(verdict_text)}</td>'
        f'<td class="english">{e(row.english)}{detail_html}</td>'
        "</tr>"
    )


def render_module_table(run: Run, module: str, rows: list[Row], wc: dict) -> str:
    e = html.escape
    header_cells = '<th class="prop">Property</th><th class="past">Past</th>'
    if run.engine_names:
        header_cells += '<th class="reach">Reach</th>'
        header_cells += "".join(f'<th class="cell">{e(en)}</th>' for en in run.engine_names)
    header_cells += '<th class="verdict">Verdict</th><th class="english">English</th>'
    body = "\n".join(render_row(run, r, wc) for r in rows)
    return (
        f'<h3>{e(module)}</h3>'
        f'<div class="table-wrap"><table><thead><tr>{header_cells}</tr></thead>'
        f"<tbody>{body}</tbody></table></div>"
    )


def render_run(idx: int, run: Run, wc: dict, checked: bool) -> tuple[str, str]:
    e = html.escape
    tab_input = f'<input type="radio" name="runtab" id="run{idx}" class="tab-input"{" checked" if checked else ""}>'
    tab_label = f'<label for="run{idx}" class="tab-label">{e(run.ts)}</label>'
    tables = "\n".join(
        render_module_table(run, mod, rows, wc) for mod, rows in run.modules.items()
    )
    content = (
        f'<section id="content{idx}" class="tab-content">'
        f'<div class="run-config">{e(run.config)}</div>'
        f'<div class="run-summary">{summary_badges(run.summary)}</div>'
        f"{tables}"
        f"</section>"
    )
    return tab_input, tab_label, content  # type: ignore[return-value]


CSS = """
:root {
  --bg: #0f1115; --panel: #161923; --border: #2a2f3d; --text: #e4e6eb;
  --muted: #8b91a3; --accent: #5b8cff;
  --proven: #3ddc84; --nocex: #6aa8ff; --refuted: #ff5d5d; --conflict: #ff9f43;
  --vacuous: #b39ddb; --assumed: #8b91a3; --error: #ff5d5d;
}
* { box-sizing: border-box; }
body {
  background: var(--bg); color: var(--text);
  font: 13px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  margin: 0; padding: 24px;
}
h1 { font-size: 16px; margin: 0 0 4px; }
.subtitle { color: var(--muted); margin: 0 0 20px; font-size: 12px; }
.tabs { display: flex; flex-wrap: wrap; gap: 2px; border-bottom: 1px solid var(--border); margin-bottom: 16px; }
.tab-input { display: none; }
.tab-label {
  padding: 6px 10px; cursor: pointer; color: var(--muted);
  border: 1px solid transparent; border-bottom: none; border-radius: 4px 4px 0 0;
  font-size: 12px; white-space: nowrap;
}
.tab-label:hover { color: var(--text); }
.tab-content { display: none; }
.run-config { color: var(--muted); font-size: 11px; margin-bottom: 8px; }
.run-summary { margin-bottom: 16px; }
.badge {
  display: inline-block; padding: 2px 7px; margin: 0 6px 4px 0;
  border-radius: 3px; font-size: 11px; border: 1px solid var(--border);
}
.badge-tail { color: var(--muted); font-size: 11px; }
h3 { font-size: 13px; margin: 20px 0 6px; color: var(--accent); }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 4px; margin-bottom: 8px; }
table { border-collapse: collapse; width: 100%; min-width: 600px; }
th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--muted); font-weight: 600; font-size: 11px; text-transform: uppercase; position: sticky; top: 0; background: var(--panel); }
td.past, td.reach, th.past, th.reach { text-align: center; white-space: nowrap; }
td.cell, th.cell { text-align: center; white-space: nowrap; font-size: 12px; }
td.prop { white-space: nowrap; font-weight: 600; }
td.english { color: var(--muted); }
td.verdict { font-weight: 700; white-space: nowrap; }
.v-PROVEN { color: var(--proven); }
.v-NOCEX { color: var(--nocex); }
.v-REFUTED, .v-ERROR, .v-FAIL { color: var(--refuted); }
.v-CONFLICT { color: var(--conflict); }
.v-VACUOUS, .v-FOLDED { color: var(--vacuous); }
.v-ASSUMED { color: var(--assumed); }
.verdict.disagree { text-decoration: underline wavy var(--conflict); }
.badge.v-PROVEN { border-color: var(--proven); color: var(--proven); }
.badge.v-NOCEX { border-color: var(--nocex); color: var(--nocex); }
.badge.v-REFUTED, .badge.v-ERROR, .badge.v-FAIL { border-color: var(--refuted); color: var(--refuted); }
.badge.v-CONFLICT { border-color: var(--conflict); color: var(--conflict); }
.badge.v-VACUOUS, .badge.v-FOLDED { border-color: var(--vacuous); color: var(--vacuous); }
.badge.v-ASSUMED { border-color: var(--assumed); color: var(--assumed); }
details { display: inline; }
summary { display: inline; cursor: pointer; color: var(--accent); font-size: 11px; margin-left: 6px; }
details pre {
  display: block; white-space: pre-wrap; background: #0a0c10; border: 1px solid var(--border);
  border-radius: 4px; padding: 8px; margin: 6px 0 0; font-size: 11px; color: var(--text);
}
"""


def build_html(runs: list[Run], wc: dict) -> str:
    # Newest run first; default-open the newest.
    ordered = list(enumerate(runs))[::-1]
    inputs, labels, contents = [], [], []
    for i, (orig_idx, run) in enumerate(ordered):
        tab_input, tab_label, content = render_run(i, run, wc, checked=(i == 0))
        inputs.append(tab_input)
        labels.append(tab_label)
        contents.append(content)

    css_show = "\n".join(
        f"#run{i}:checked ~ #content{i} {{ display: block; }}\n"
        f'#run{i}:checked ~ .tabs label[for="run{i}"] {{ '
        f"color: var(--text); border-color: var(--border); background: var(--panel); }}"
        for i in range(len(ordered))
    )

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>mallet dashboard</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
{CSS}
{css_show}
</style>
</head>
<body>
<h1>mallet dashboard</h1>
<p class="subtitle">generated/mallet-report.log + generated/mallet-matrix.jsonl (wallclock on hover) &middot; regenerate with <code>python3 scripts/mallet_dashboard.py</code></p>
{''.join(inputs)}
<div class="tabs">
{''.join(labels)}
</div>
{''.join(contents)}
</body>
</html>
"""


def main() -> None:
    text = REPORT_LOG.read_text() if REPORT_LOG.exists() else ""
    runs = parse_report_log(text)
    wc = parse_wallclocks(MATRIX_JSONL)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(build_html(runs, wc))
    print(f"wrote {OUT} ({len(runs)} runs)")


if __name__ == "__main__":
    main()
