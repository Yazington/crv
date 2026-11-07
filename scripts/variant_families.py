"""Check whether same-title records are variants (colour/size) of one product.

Usage: python3 -I scripts/variant_families.py data/raw/products.json
"""
import json, sys
from collections import Counter, defaultdict

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

rows = list(records(sys.argv[1]))
fam = defaultdict(list)
for r in rows:
    t = (r["title"] or "").strip().lower()
    if t:
        fam[(t, (r["store"] or "").lower())].append(r)
multi = {k: g for k, g in fam.items() if len(g) > 1}
in_multi = sum(len(g) for g in multi.values())
print(f"families: {len(fam)}; multi-record families: {len(multi)} covering {in_multi} records ({in_multi/len(rows):.0%})")
uniq = sum(1 for g in multi.values() if len({(x['color'], x['size']) for x in g}) == len(g))
print(f"multi families where every record has a distinct (color,size): {uniq} / {len(multi)}")
same_cat = sum(1 for g in multi.values() if len({tuple(x['categories']) for x in g}) == 1)
print(f"multi families with identical category path: {same_cat} / {len(multi)}")
spread = sorted(max(x['price'] for x in g) / min(x['price'] for x in g) for g in multi.values())
print(f"price max/min within family: median {spread[len(spread)//2]:.2f}, p90 {spread[int(.9*len(spread))]:.2f}")
k, g = next((k, g) for k, g in multi.items() if 'arsuxeo' in k[0])
print("\nexample:", k[0][:60])
for x in sorted(g, key=lambda x: (x['color'], x['size'])):
    print(f"  {x['parent_asin']} {x['color']:>12} {x['size']:>10} ${x['price']:.2f} rating {x['average_rating']} ({x['rating_number']})")
