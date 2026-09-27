#!/usr/bin/env python3
"""Plot one replay run directory (output of `replay run|matrix`).

Usage: plot_replay.py <run_dir> [<run_dir> ...]   (writes <run_dir>/plot.png)
Requires: matplotlib (pip install -r tools/plot/requirements.txt)

Panels: (1) track: estimate vs truth, (2) error with reported r68/r95 and degraded windows,
(3) GNSS trust states over time with injected offset.
"""
import csv
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

R95_PER_R68 = 2.4477 / 1.5096
STATE_Y = {"TRUSTED": 3, "QUESTIONABLE": 2, "REJECTED": 1, "UNAVAILABLE": 0}
STATE_C = {"TRUSTED": "#2e7d32", "QUESTIONABLE": "#f9a825", "REJECTED": "#c62828", "UNAVAILABLE": "#9e9e9e"}


def f(x):
    return float(x) if x not in ("", None) else None


def r(x, n=2):
    return "–" if x is None else round(x, n)


def load(path):
    with open(path) as fh:
        return list(csv.DictReader(fh))


def plot(run: Path):
    ticks = load(run / "ticks.csv")
    trust = load(run / "trust.csv") if (run / "trust.csv").exists() else []
    summary = json.loads((run / "summary.json").read_text())

    fig = plt.figure(figsize=(14, 10))
    gs = fig.add_gridspec(2, 2, height_ratios=[1.3, 1])
    ax_map = fig.add_subplot(gs[0, 0])
    ax_err = fig.add_subplot(gs[0, 1])
    ax_tr = fig.add_subplot(gs[1, :])

    # Track
    tl = [(f(r["truth_lon"]), f(r["truth_lat"])) for r in ticks if r["truth_lat"]]
    if tl:
        ax_map.plot(*zip(*tl), color="black", lw=1.5, label="truth")
    ax_map.plot([f(r["est_lon"]) for r in ticks], [f(r["est_lat"]) for r in ticks], color="#1565c0", lw=1, label="estimate")
    deg = [r for r in ticks if r["degraded"]]
    if deg:
        ax_map.scatter([f(r["est_lon"]) for r in deg], [f(r["est_lat"]) for r in deg], s=4, color="#c62828", label="estimate (degraded)")
    ax_map.set_title("Track")
    ax_map.set_aspect("equal", adjustable="datalim")
    ax_map.legend(fontsize=8)

    # Error vs reported uncertainty
    t = [f(r["t_s"]) for r in ticks]
    r68 = [f(r["r68_m"]) for r in ticks]
    err = [f(r["err_m"]) for r in ticks]
    ax_err.fill_between(t, 0, [x * R95_PER_R68 for x in r68], color="#90caf9", alpha=0.4, label="reported r95")
    ax_err.plot(t, r68, color="#1565c0", lw=0.8, label="reported r68")
    ax_err.plot(t, [e if e is not None else float("nan") for e in err], color="black", lw=1, label="true error")
    in_win = False
    for r in ticks:
        if r["degraded"] and not in_win:
            start, in_win = f(r["t_s"]), True
        elif not r["degraded"] and in_win:
            ax_err.axvspan(start, f(r["t_s"]), color="#ffcdd2", alpha=0.5)
            in_win = False
    if in_win:
        ax_err.axvspan(start, t[-1], color="#ffcdd2", alpha=0.5)
    ax_err.set_yscale("symlog", linthresh=10)
    ax_err.set_xlabel("t [s]")
    ax_err.set_ylabel("m")
    ax_err.set_title(f"Error  p95={r(summary.get('p95M'), 1)} m  within95={r(summary.get('within95'))}")
    ax_err.legend(fontsize=8)

    # Trust timeline
    gnss = [r for r in trust if r["source"] == "GNSS"]
    for st, y in STATE_Y.items():
        pts = [f(r["t_s"]) for r in gnss if r["state"] == st]
        ax_tr.scatter(pts, [y] * len(pts), s=6, color=STATE_C[st], label=st)
    ax_tr.set_yticks(list(STATE_Y.values()), list(STATE_Y.keys()))
    ax2 = ax_tr.twinx()
    ax2.plot([f(r["t_s"]) for r in gnss], [f(r["injected_offset_m"]) or 0 for r in gnss], color="#6a1b9a", lw=0.8)
    ax2.set_ylabel("injected offset [m]", color="#6a1b9a")
    tm = summary.get("trust", {})
    ax_tr.set_title(f"GNSS trust  false-reject={r(tm.get('falseRejectionRate'), 3)}  missed={r(tm.get('missedDetectionRate'), 3)}")
    ax_tr.set_xlabel("t [s]")

    fig.suptitle(f"{summary['scenario']} / {summary['variant']}")
    fig.tight_layout()
    out = run / "plot.png"
    fig.savefig(out, dpi=110)
    plt.close(fig)
    print(out)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for d in sys.argv[1:]:
        plot(Path(d))
