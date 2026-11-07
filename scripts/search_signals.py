"""Measure what makes search fast and reliable, and what can drive recommendations.

Usage: python3 -I scripts/search_signals.py data/raw/products.json
"""

import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

AUDIENCE = {"Women", "Men", "Boys", "Girls", "Baby"}


def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)


def has_word(text, *words):
    return any(re.search(rf"\b{w}\b", text) for w in words)


def spearman(xs, ys):
    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        for pos, i in enumerate(order):
            r[i] = pos
        return r
    rx, ry = ranks(xs), ranks(ys)
    n = len(xs)
    mx, my = sum(rx) / n, sum(ry) / n
    cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    return cov / math.sqrt(sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry))


rows = list(records(sys.argv[1]))
n = len(rows)
title = lambda r: (r["title"] or "").lower()
path = lambda r: r["categories"][1:]  # drop the root "Clothing, Shoes & Jewelry"

# A. How much does each filter narrow the catalog?
print("A. Filter selectivity (share of variants kept)")
filters = {
    "price < $120": lambda r: r["price"] < 120,
    "price < $50": lambda r: r["price"] < 50,
    "rating >= 4.0": lambda r: r["average_rating"] >= 4.0,
    "rating >= 4.0 and >= 20 reviews": lambda r: r["average_rating"] >= 4.0 and r["rating_number"] >= 20,
    "audience = Women (category path)": lambda r: len(r["categories"]) > 1 and r["categories"][1] == "Women",
    "brand = Nike (store)": lambda r: (r["store"] or "").lower() == "nike",
    "type = running shoes (leaf Road/Trail Running)": lambda r: r["categories"][-1] in ("Road Running", "Trail Running"),
    "running shoes AND price < $120 AND Women": lambda r: r["categories"][-1] in ("Road Running", "Trail Running") and r["price"] < 120 and r["categories"][1] == "Women",
}
for name, f in filters.items():
    k = sum(1 for r in rows if f(r))
    print(f"  {name:48} {k:6} ({k / n:6.1%})")

# B. Product type from the category path
print("\nB. Product type from the category path")
internal = set()
for r in rows:
    for i in range(1, len(r["categories"]) - 1):
        internal.add(tuple(r["categories"][: i + 1]))
truncated = sum(1 for r in rows if tuple(r["categories"]) in internal)
print(f"  paths that stop at a node which has children elsewhere: {truncated} ({truncated / n:.1%})")
aud_leaf = [r for r in rows if r["categories"][-1] in AUDIENCE]
print(f"  paths ending in an audience word (Men, Women...): {len(aud_leaf)}")
print("   e.g.", Counter(" > ".join(path(r)) for r in aud_leaf).most_common(4))


def product_type(r):
    # deepest node that is not an audience word
    for node in reversed(r["categories"][1:]):
        if node not in AUDIENCE:
            return node
    return None


types = Counter(product_type(r) for r in rows)
print(f"  distinct product types (deepest non-audience node): {len(types)}")
cum, need = 0, None
for i, (_, c) in enumerate(types.most_common(), 1):
    cum += c
    if need is None and cum / n >= 0.9:
        need = i
print(f"  types needed to cover 90% of variants: {need}")

# C. Do variants of one product share their text?
print("\nC. Text shared across variants (families = same title + store)")
fam = defaultdict(list)
for r in rows:
    if title(r):
        fam[(title(r), (r["store"] or "").lower())].append(r)
multi = [g for g in fam.values() if len(g) > 1]
same_feat = sum(1 for g in multi if len({json.dumps(x["features"]) for x in g}) == 1)
same_desc = sum(1 for g in multi if len({json.dumps(x["description"]) for x in g}) == 1)
same_img = sum(1 for g in multi if len({x["images"][0]["large"] for x in g if x["images"]}) == 1)
print(f"  multi-variant families: {len(multi)}; identical features {same_feat / len(multi):.0%}, identical description {same_desc / len(multi):.0%}, identical main image {same_img / len(multi):.0%}")

# D. Boilerplate and keyword stuffing
print("\nD. Boilerplate and keyword stuffing")
feat = Counter(f.strip().lower() for r in rows for f in r["features"] if f.strip())
total_feat = sum(feat.values())
boiler = {f for f, c in feat.items() if c >= 200}
boiler_hits = sum(feat[f] for f in boiler)
print(f"  feature lines: {total_feat}; lines repeated in >= 200 products: {len(boiler)} distinct, {boiler_hits / total_feat:.0%} of all lines")
print("   top:", [f[:30] for f, _ in feat.most_common(10)])
desc = Counter(d.strip().lower() for r in rows for d in r["description"] if d.strip())
print("   top description lines:", [(d[:30], c) for d, c in desc.most_common(6)])
both = sum(1 for r in rows if has_word(title(r), "men", "mens", "men's") and has_word(title(r), "women", "womens", "women's"))
print(f"  titles naming both men and women: {both} ({both / n:.0%})")
long_t = sum(1 for r in rows if len(r["title"] or "") > 150)
print(f"  titles over 150 characters: {long_t} ({long_t / n:.0%})")

# F. Does the category agree with the title?
print("\nF. Title keyword vs category (reliability of product type)")
for word, pat in [("shorts", r"\bshorts\b"), ("running shoe", r"\brunning shoes?\b"), ("backpack", r"\bbackpacks?\b"), ("sunglasses", r"\bsunglasses\b"), ("rain jacket", r"\brain jackets?\b")]:
    hit = [r for r in rows if re.search(pat, title(r))]
    print(f"  title has '{word}': {len(hit):5}; top types {Counter(product_type(r) for r in hit).most_common(5)}")

# E. Recommendation signals
print("\nE. Recommendation signals")
print(f"  bought_together filled: {sum(1 for r in rows if r.get('bought_together'))}")
ranks, counts = [], []
for r in rows:
    bsr = r["details"].get("Best Sellers Rank")
    if isinstance(bsr, dict) and bsr:
        top = min(bsr.values())  # best (lowest) rank across the listed categories
        main = max(bsr.values())  # overall rank is the largest number
        ranks.append(main)
        counts.append(r["rating_number"])
print(f"  best-seller rank usable: {len(ranks)} ({len(ranks) / n:.0%})")
if ranks:
    rho = spearman([-x for x in ranks], counts)
    print(f"  Spearman(better overall rank, review count): {rho:.2f}")
rn = sorted(r["rating_number"] for r in rows)
print(f"  review count: median {rn[n // 2]}, p90 {rn[int(.9 * n)]}, share with >= 50 reviews {sum(x >= 50 for x in rn) / n:.0%}")
mean_rating = sum(r["average_rating"] * r["rating_number"] for r in rows) / sum(r["rating_number"] for r in rows)
print(f"  review-weighted mean rating (prior for a Bayesian average): {mean_rating:.2f}")
img_owner = defaultdict(set)
for r in rows:
    for im in r["images"]:
        if im.get("variant") == "MAIN":
            img_owner[im["large"]].add((title(r), (r["store"] or "").lower()))
shared_img = [u for u, owners in img_owner.items() if len(owners) > 1]
print(f"  main images shared by different titles: {len(shared_img)} images")
years = Counter(datetime.fromtimestamp(r["date_first_available"] / 1000, timezone.utc).year for r in rows)
print("  first-available year:", sorted(years.items()))
stores = Counter((r["store"] or "").lower() for r in rows)
store_types = defaultdict(set)
for r in rows:
    store_types[(r["store"] or "").lower()].add(product_type(r))
big = [s for s, c in stores.items() if c >= 5]
print(f"  stores with >= 5 variants: {len(big)}; of those spanning >= 3 product types: {sum(1 for s in big if len(store_types[s]) >= 3)}")
