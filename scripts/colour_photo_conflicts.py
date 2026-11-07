"""Count variants whose main photo is shared with a differently coloured variant of the same product.

If a blue variant and an orange variant share one main image, the photo can't match both colour
fields. Families here are same title + store.

Usage: python3 -I scripts/colour_photo_conflicts.py data/raw/products.json
"""
import json, sys
from collections import defaultdict

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

def main_image(r):
    return next((i["large"] for i in r["images"] if i.get("variant") == "MAIN"), None)

fam = defaultdict(list)
for r in records(sys.argv[1]):
    if (r["title"] or "").strip():
        fam[((r["title"] or "").strip().lower(), (r["store"] or "").lower())].append(r)

multi = [g for g in fam.values() if len(g) > 1]
multi_colour = [g for g in multi if len({r["color"].strip().lower() for r in g}) > 1]
conflicts, examples = 0, []
for g in multi_colour:
    by_img = defaultdict(set)
    for r in g:
        by_img[main_image(r)].add(r["color"].strip())
    for img, colours in by_img.items():
        if img and len({c.lower() for c in colours}) > 1:
            n = sum(1 for r in g if main_image(r) == img)
            conflicts += n
            if len(examples) < 6:
                examples.append((g[0]["title"][:50], sorted(colours)[:4], img))
total = sum(len(g) for g in multi_colour)
print(f"multi-variant products with more than one colour: {len(multi_colour)} ({total} variants)")
print(f"variants sharing a main photo with a different colour: {conflicts} ({conflicts / total:.0%} of those variants)")
for t, c, img in examples:
    print(f"  {t!r}: colours {c} share {img}")
