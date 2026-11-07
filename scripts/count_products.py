"""Estimate how many distinct products (not variants) the catalog holds.

Usage: python3 -I scripts/count_products.py data/raw/products.json
"""
import json, re, sys
from collections import Counter, defaultdict

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

rows = list(records(sys.argv[1]))
norm = lambda s: re.sub(r"[^a-z0-9 ]+", " ", (s or "").lower())

def strip_variant(r):
    t = " " + norm(r["title"]) + " "
    for v in (r["color"], r["size"]):
        v = norm(v).strip()
        if len(v) > 1:
            t = t.replace(" " + v + " ", " ")
    return " ".join(t.split())

keys = {
    "A exact title + store": lambda r: (norm(r["title"]).strip(), norm(r["store"])),
    "B title minus colour/size words + store": lambda r: (strip_variant(r), norm(r["store"])),
    "C store + shared review signature + first 4 title words": lambda r: (norm(r["store"]), r["average_rating"], r["rating_number"], " ".join(norm(r["title"]).split()[:4])),
}
for name, key in keys.items():
    groups = Counter(key(r) for r in rows)
    print(f"{name:58} {len(groups):6} products  (largest group {max(groups.values())})")

# Union of A, B and C (any signal links two records)
parent = list(range(len(rows)))
def find(i):
    while parent[i] != i:
        parent[i] = parent[parent[i]]
        i = parent[i]
    return i
for key in keys.values():
    first = {}
    for i, r in enumerate(rows):
        k = key(r)
        if k in first:
            parent[find(i)] = find(first[k])
        else:
            first[k] = i
# also link records that share an observed child ASIN
owner = {}
shared = 0
for i, r in enumerate(rows):
    for c in r["observed_child_asins"]:
        if c in owner:
            shared += 1
            parent[find(i)] = find(owner[c])
        else:
            owner[c] = i
roots = Counter(find(i) for i in range(len(rows)))
sizes = Counter(roots.values())
print(f"\nchild ASINs shared between records: {shared}")
print(f"union of all signals: {len(roots)} products; singletons {sizes[1]}; 2-5 variants {sum(v for k, v in sizes.items() if 2 <= k <= 5)}; 6+ variants {sum(v for k, v in sizes.items() if k >= 6)}; largest {max(roots.values())}")

# Show a few groups that B or C merged but A did not, to sanity-check precision
a = keys["A exact title + store"]
shown = 0
groups = defaultdict(list)
for i in range(len(rows)):
    groups[find(i)].append(rows[i])
for g in groups.values():
    if len({a(x) for x in g}) > 1 and 2 < len(g) < 8:
        print("\n  merged:", [ (x["title"][:55], x["color"][:14], x["size"][:10]) for x in g[:4]])
        shown += 1
        if shown == 4:
            break
