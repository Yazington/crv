"""Targeted data-quality checks on the raw catalog.

Usage: python3 -I scripts/quality_checks.py data/raw/products.json
"""
import json, re, sys
from collections import Counter

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

rows = list(records(sys.argv[1]))
n = len(rows)
NAN = {"nan", "none", "null", "n/a", "na", ""}

# 1. price vs original_price
both = [(r["price"], r["original_price"]) for r in rows if r.get("original_price") is not None]
eq = sum(1 for p, o in both if abs(p - o) < 0.005)
ratio = sorted(p / o for p, o in both if o)
print(f"original_price present: {len(both)}; price == original_price: {eq}")
if ratio:
    print("  price/original ratio pct:", {q: round(ratio[int(q/100*(len(ratio)-1))], 3) for q in (0, 10, 50, 90, 100)})
cents = Counter(f"{r['price']:.2f}"[-2:] for r in rows)
print("  most common cents endings:", cents.most_common(6))

# 2. 'nan' strings across all string fields
nan_counts = Counter()
for r in rows:
    for k, v in r.items():
        if isinstance(v, str) and v.strip().lower() in NAN:
            nan_counts[k] += 1
print("\n'nan'-like strings per field:", nan_counts.most_common())

# 3. color/size: details vs top-level, and junk values
mismatch_c = sum(1 for r in rows if str(r["details"].get("Color")) != r["color"])
mismatch_s = sum(1 for r in rows if str(r["details"].get("Size")) != r["size"])
print(f"\ncolor != details.Color: {mismatch_c}; size != details.Size: {mismatch_s}")
def show(field, pred, label, k=10):
    hits = [r[field] for r in rows if pred(r[field])]
    print(f"  {field} {label}: {len(hits)}  e.g. {Counter(hits).most_common(k)}")
show("color", lambda v: len(v) > 30, "longer than 30 chars", 5)
show("color", lambda v: bool(re.search(r"\d", v)), "with digits", 8)
show("size", lambda v: len(v) > 30, "longer than 30 chars", 5)
color_in_title = sum(1 for r in rows if r["color"].lower() in NAN or r["color"].split()[0].lower() in (r["title"] or "").lower())
print(f"  color word appears in title (or color missing): {color_in_title} ({color_in_title/n:.0%})")

# 4. duplicate titles
by_title = {}
for r in rows:
    by_title.setdefault((r["title"] or "").strip().lower(), []).append(r)
dups = sorted(((t, g) for t, g in by_title.items() if len(g) > 1 and t), key=lambda x: -len(x[1]))
print(f"\ntitles shared by >1 product: {len(dups)} groups")
for t, g in dups[:6]:
    print(f"  x{len(g):3} {t[:70]!r}  stores={Counter(x['store'] for x in g).most_common(2)}  colors={[x['color'] for x in g[:4]]}  prices={[x['price'] for x in g[:4]]}")

# 5. text quality
empty_desc = sum(1 for r in rows if not any((d or "").strip() for d in r["description"]))
html = sum(1 for r in rows if re.search(r"<[a-z/][^>]*>|&[a-z]+;|&#\d+;", r["title"] + " ".join(r["description"]) + " ".join(r["features"])))
caps = sum(1 for r in rows if r["title"] and r["title"].isupper())
nonascii = sum(1 for r in rows if re.search(r"[^\x00-\x7F]", r["title"] or ""))
print(f"\ndescription effectively empty: {empty_desc} ({empty_desc/n:.0%}); features empty: {sum(1 for r in rows if not r['features'])}")
print(f"HTML tags/entities in text: {html}; ALL-CAPS titles: {caps}; non-ASCII in title: {nonascii}")
print("  empty titles:", [(r["parent_asin"], r["store"], r["categories"][-1:]) for r in rows if not (r["title"] or "").strip()])

# 6. ratings with tiny sample sizes
low_n = sum(1 for r in rows if r["rating_number"] < 5)
print(f"\nratings based on < 5 reviews: {low_n} ({low_n/n:.0%})")

# 7. categories: junk nodes and leaf distribution
junk = Counter(c for r in rows for c in r["categories"] if re.search(r"\btest\b|green lit|amazon fashion \d|^\d+$", c, re.I))
print("\njunk category nodes:", junk.most_common(10))
leaf = Counter(r["categories"][-1] for r in rows if r["categories"])
print(f"distinct leaf categories: {len(leaf)}; top 25:")
for c, k in leaf.most_common(25):
    print(f"  {k:5} {c}")

# 8. images
no_hires = sum(1 for r in rows if not any(i.get("hi_res") for i in r["images"]))
no_main = sum(1 for r in rows if not any(i.get("variant") == "MAIN" for i in r["images"]))
print(f"\nno hi_res image: {no_hires}; no MAIN image: {no_main}")

# 9. date_first_available range
from datetime import datetime, timezone
ds = sorted(r["date_first_available"] for r in rows)
fmt = lambda ms: datetime.fromtimestamp(ms / 1000, timezone.utc).date()
print(f"date_first_available: {fmt(ds[0])} .. {fmt(ds[-1])}")

# 10. sample record keys in 'details' that duplicate top-level
print("\nsample details:", json.dumps(rows[123]["details"])[:600])
