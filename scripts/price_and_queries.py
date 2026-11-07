"""Compare price vs original_price, and dry-run the brief's example queries.

Usage: python3 -I scripts/price_and_queries.py data/raw/products.json
"""
import json, re, sys, statistics
from collections import Counter, defaultdict
from datetime import datetime, timezone

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

rows = list(records(sys.argv[1]))
pct = lambda xs: {q: round(sorted(xs)[int(q/100*(len(xs)-1))], 2) for q in (0, 1, 10, 50, 90, 99, 100)}

orig = [r["original_price"] for r in rows if r.get("original_price") is not None]
print("original_price pct:", pct(orig))
print("price pct (all):   ", pct([r["price"] for r in rows]))
print("price pct where original exists:", pct([r["price"] for r in rows if r.get("original_price") is not None]))

# Is synthetic price category-aware? median per leaf
by_leaf = defaultdict(list)
for r in rows:
    if r["categories"]:
        by_leaf[r["categories"][-1]].append(r)
print("\nleaf: median price / median original_price (n)")
for leaf in ["Keychains", "Bandanas", "Athletic Socks", "Road Running", "Fashion Sneakers", "Bracelets", "Wallets", "Carry-Ons", "Suitcases", "Down & Parkas", "Watches", "Rings"]:
    g = by_leaf.get(leaf, [])
    if g:
        o = [x["original_price"] for x in g if x.get("original_price") is not None]
        print(f"  {leaf:18} {statistics.median(x['price'] for x in g):7.2f} / {statistics.median(o) if o else float('nan'):7.2f}  (n={len(g)}, orig n={len(o)})")

# Future / odd dates
now_ms = datetime(2026, 11, 7, tzinfo=timezone.utc).timestamp() * 1000
fut = [r for r in rows if r["date_first_available"] > now_ms]
print(f"\nfuture date_first_available: {len(fut)}  e.g. {[(r['date_first_available'], r['details'].get('Date First Available')) for r in fut[:3]]}")

# Example query 1: running shoes under $120
def text(r):
    return " ".join([r["title"] or "", " ".join(r["categories"])]).lower()
running_shoes = [r for r in rows if "running" in text(r) and re.search(r"\bshoes?\b|sneaker|trainer", text(r)) and "sock" not in text(r)]
print(f"\nQ1 'running shoes': {len(running_shoes)}; under $120: {sum(1 for r in running_shoes if r['price'] < 120)}")
print("   leaf cats:", Counter(r["categories"][-1] for r in running_shoes).most_common(6))

# Example query 2: orange running shorts size small
shorts = [r for r in rows if "running" in text(r) and "short" in text(r)]
orange = [r for r in shorts if "orange" in (r["color"] + " " + r["title"]).lower()]
small = [r for r in orange if re.search(r"\bsmall\b|^s$|\bs\b", r["size"].lower())]
print(f"\nQ2 'running shorts': {len(shorts)}; orange: {len(orange)}; listed size small: {len(small)}")
for r in orange[:8]:
    print(f"   {r['parent_asin']} ${r['price']:.2f} color={r['color']!r} size={r['size']!r} children={len(r['observed_child_asins'])} :: {r['title'][:60]}")

# Colour vocabulary: how many map to a basic colour word
basic = ["black","white","blue","red","green","pink","grey","gray","brown","silver","gold","purple","orange","yellow","navy","beige","multi","khaki","tan"]
hit = sum(1 for r in rows if any(b in r["color"].lower() for b in basic))
print(f"\ncolor contains a basic colour word: {hit} ({hit/len(rows):.0%})")
print("observed_child_asins length pct (scanned only):", pct([len(r["observed_child_asins"]) for r in rows if r["child_lookup_status"] == "scanned"]))
