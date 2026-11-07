"""Score each record on how good and complete its listing is, across many fields.

Each check is worth one point. The score is the share of checks passed.

Usage: python3 -I scripts/quality_score.py data/raw/products.json
"""
import json, sys
from collections import Counter

MISSING = {"nan", "none", "null", ""}

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

def filled(v):
    return isinstance(v, str) and v.strip().lower() not in MISSING

CHECKS = {
    "clear title (10-150 chars)": lambda r: 10 <= len(r["title"] or "") <= 150,
    "has features": lambda r: len(r["features"]) >= 3,
    "real description (> 200 chars)": lambda r: len(" ".join(r["description"])) > 200,
    "brand known": lambda r: filled(r["store"]) or filled(r["brand"]),
    "material listed": lambda r: filled(r["material"]),
    "3+ images": lambda r: len(r["images"]) >= 3,
    "hi-res image": lambda r: any(i.get("hi_res") for i in r["images"]),
    "rating from 5+ reviews": lambda r: r["rating_number"] >= 5,
    "rating 4.0+": lambda r: r["average_rating"] >= 4.0,
}

rows = list(records(sys.argv[1]))
n = len(rows)
print("checks passed (share of records):")
for name, check in CHECKS.items():
    print(f"  {name:32} {sum(1 for r in rows if check(r)) / n:5.0%}")
scores = Counter(sum(check(r) for check in CHECKS.values()) for r in rows)
print(f"\nscore out of {len(CHECKS)}:")
for s in range(len(CHECKS) + 1):
    print(f"  {s}: {scores[s]:6} ({scores[s] / n:4.0%}) {'#' * round(60 * scores[s] / n)}")
