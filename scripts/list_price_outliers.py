"""Flag implausible original_price (list price) values against their category's typical prices.

Uses a modified z-score (median and MAD, the median absolute deviation, of log price) per
category leaf, falling back to the parent node when a leaf has too few prices. |z| > 3.5 is the
usual cut for this score.

Usage: python3 -I scripts/list_price_outliers.py data/raw/products.json
"""
import json, math, statistics, sys
from collections import defaultdict

MIN_GROUP = 20
Z_CUT = 3.5
# Floor on the spread (log units, about x1.35) so categories with near-identical prices
# don't flag ordinary prices.
MAD_FLOOR = 0.3

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

rows = [r for r in records(sys.argv[1]) if r.get("original_price") is not None]
by_node = defaultdict(list)
for r in rows:
    for depth in range(1, len(r["categories"]) + 1):
        by_node[tuple(r["categories"][:depth])].append(math.log(r["original_price"]))

def stats(r):
    for depth in range(len(r["categories"]), 0, -1):
        xs = by_node[tuple(r["categories"][:depth])]
        if len(xs) >= MIN_GROUP:
            med = statistics.median(xs)
            mad = max(statistics.median(abs(x - med) for x in xs), MAD_FLOOR)
            return med, mad
    return None

flagged = []
for r in rows:
    s = stats(r)
    if s:
        z = 0.6745 * (math.log(r["original_price"]) - s[0]) / s[1]
        if abs(z) > Z_CUT:
            flagged.append((z, r))
flagged.sort(key=lambda x: x[0])
print(f"list prices: {len(rows)}; flagged: {len(flagged)} ({len(flagged)/len(rows):.1%}); low {sum(z < 0 for z, _ in flagged)}, high {sum(z > 0 for z, _ in flagged)}")
for z, r in flagged[:5] + flagged[-5:]:
    print(f"  z={z:+5.1f} ${r['original_price']:>8.2f} (catalog ${r['price']:.2f})  {r['categories'][-1]:22} {r['title'][:50]}")
