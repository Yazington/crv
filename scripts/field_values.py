"""Show the most common values of selected top-level fields.

Usage: python3 -I scripts/field_values.py data/raw/products.json field1 field2 ...
"""
import json, sys
from collections import Counter

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)

path, fields = sys.argv[1], sys.argv[2:]
counts = {k: Counter() for k in fields}
n = 0
for r in records(path):
    n += 1
    for k in fields:
        v = r.get(k)
        counts[k][json.dumps(v)[:90] if not isinstance(v, str) else v[:90]] += 1
for k in fields:
    c = counts[k]
    print(f"\n== {k}: {len(c)} distinct")
    for v, cnt in c.most_common(12):
        print(f"  {cnt:6} ({cnt/n:5.1%})  {v!r}")
