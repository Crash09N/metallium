#!/usr/bin/env python3
# Copyright (c) 2026 Nativium contributors, licensed under the Apache License 2.0.
"""Per-encoder GPU time from an Instruments "Metal System Trace" (.trace) of a Nativium client.

Record (while a bench runs; see docs/design/iris-stage5-compute.md, "Performance"):
    xcrun xctrace record --template 'Metal System Trace' --attach <pid> --time-limit 2s --output run.trace
Then:
    python3 -I tools/metal-trace-encoders.py run.trace [process-substring, default java]

Prints, for the command buffers sharing the most common encoder layout (= the steady-state frame), the average
vertex/fragment/compute GPU time of every encoder in submission order, their sum and the median command-buffer wall
time. Map encoder indices to passes with a -PperfTrace run (logs every encoder of one submit with its caller).
Blit encoders show up on the Compute channel.
"""
import collections
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def export(trace):
    xpath = '/trace-toc/run[@number="1"]/data/table[@schema="metal-gpu-intervals"]'
    return subprocess.run(["xcrun", "xctrace", "export", "--input", trace, "--xpath", xpath],
                          check=True, capture_output=True).stdout


def intervals(xml_bytes, process):
    ids, cols, out = {}, None, []
    root = ET.fromstring(xml_bytes)
    for el in root.iter():
        if el.get("id") is not None:
            ids[el.get("id")] = el
    for el in root.iter():
        if el.tag == "schema":
            cols = [c.findtext("mnemonic") for c in el.findall("col")]
        elif el.tag == "row":
            vals = [ids[c.get("ref")] if c.get("ref") else c for c in el]
            row = dict(zip(cols, vals))
            proc = row.get("process")
            if proc is None or process not in (proc.get("fmt") or ""):
                continue
            label = row["event-label"].get("fmt") or ""
            m = re.search(r"Command Buffer \d+:(\w+) Command (\d+)", label)
            if not m:
                continue
            out.append((int(row["start"].text), int(row["duration"].text), row["channel-name"].get("fmt"),
                        row["cmdbuffer-id"].get("fmt"), m.group(1), int(m.group(2))))
    return sorted(out)


def summarize(rows):
    cbs = collections.OrderedDict()
    for start, dur, channel, cb, kind, index in rows:
        e = cbs.setdefault(cb, collections.OrderedDict()).setdefault(index, {"kind": kind, "Vertex": 0, "Fragment": 0, "Compute": 0,
                                                                          "first": start, "last": start})
        e[channel] = e.get(channel, 0) + dur
        e["first"] = min(e["first"], start)
        e["last"] = max(e["last"], start + dur)
    layouts = collections.Counter(tuple((i, e["kind"]) for i, e in encs.items()) for encs in cbs.values())
    layout, count = layouts.most_common(1)[0]
    frames = [encs for encs in cbs.values() if tuple((i, e["kind"]) for i, e in encs.items()) == layout]
    print(f"command buffers: {len(cbs)}, steady-state layout: {count}")
    total = 0.0
    for index, kind in layout:
        v, f, c = (sum(fr[index][ch] for fr in frames) / len(frames) / 1e6 for ch in ("Vertex", "Fragment", "Compute"))
        total += v + f + c
        print(f"{index:3d} {kind:8s} vertex={v:6.3f} fragment={f:6.3f} compute={c:6.3f} sum={v + f + c:6.3f} ms")
    walls = sorted((max(e["last"] for e in fr.values()) - min(e["first"] for e in fr.values())) / 1e6 for fr in frames)
    print(f"sum of encoder times {total:.3f} ms, command buffer wall median {walls[len(walls) // 2]:.3f} ms")


def overlap(rows):
    """GPU overlap between consecutive command buffers (docs/design/gpu-overlap.md)."""
    cbs = collections.OrderedDict()
    for start, dur, channel, cb, kind, index in rows:
        cbs.setdefault(cb, []).append((start, start + dur))
    # Frame command buffers only (VSync off also commits a tiny present command buffer per shown frame).
    biggest = max(sum(b - a for a, b in iv) for iv in cbs.values())
    frames = [iv for iv in cbs.values() if sum(b - a for a, b in iv) >= 0.2 * biggest]
    spans = sorted((min(a for a, _ in iv), max(b for _, b in iv), sum(b - a for a, b in iv)) for iv in frames)
    if len(spans) < 3:
        return
    every = sorted((a, b) for iv in frames for a, b in iv)
    union, cur_a, cur_b = 0, None, None
    for a, b in every:
        if cur_b is None or a > cur_b:
            if cur_b is not None:
                union += cur_b - cur_a
            cur_a, cur_b = a, b
        else:
            cur_b = max(cur_b, b)
    union += cur_b - cur_a
    window = every[-1][1] - every[0][0]
    busy = sum(s for _, _, s in spans)
    gaps = sorted((spans[i][0] - spans[i - 1][0]) / 1e6 for i in range(1, len(spans)))
    over = sorted(max(0, spans[i - 1][1] - spans[i][0]) / 1e6 for i in range(1, len(spans)))
    n = len(spans)
    print(f"overlap: {n} command buffers, median start-to-start {gaps[len(gaps) // 2]:.3f} ms, "
          f"median overlap with the previous one {over[len(over) // 2]:.3f} ms ({sum(1 for o in over if o > 0)}/{len(over)} overlap)")
    print(f"overlap: GPU busy (union of all intervals) {union / 1e6 / n:.3f} ms per command buffer, channel time "
          f"{busy / 1e6 / n:.3f} ms per command buffer (concurrency {busy / union:.2f}), busy {100.0 * union / window:.0f}% of the window")


if __name__ == "__main__":
    rows = intervals(export(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else "java")
    summarize(rows)
    overlap(rows)
