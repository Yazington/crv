"""Profile the raw catalog: field coverage, types, and value distributions.

Usage: python3 -I scripts/profile_catalog.py data/raw/products.json
"""

import json
import statistics
import sys
from collections import Counter


def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line in ("[", "]", ""):
                continue
            yield json.loads(line)


def is_empty(v):
    return v is None or v == "" or v == [] or v == {}


def main(path):
    n = 0
    present, empty, types = Counter(), Counter(), {}
    main_cat, stores, top_cat, cat_depth = Counter(), Counter(), Counter(), Counter()
    detail_keys, price_types = Counter(), Counter()
    prices, ratings, rating_counts, n_images, title_len = [], [], [], [], []
    asins = Counter()
    titles = Counter()
    samples_bad_price = []

    for r in records(path):
        n += 1
        for k, v in r.items():
            present[k] += 1
            if is_empty(v):
                empty[k] += 1
            types.setdefault(k, Counter())[type(v).__name__] += 1
        main_cat[r.get("main_category")] += 1
        stores[r.get("store")] += 1
        cats = r.get("categories") or []
        cat_depth[len(cats)] += 1
        top_cat[" > ".join(cats[:2]) if cats else "<none>"] += 1
        for k in (r.get("details") or {}):
            detail_keys[k] += 1
        p = r.get("price")
        price_types[type(p).__name__] += 1
        if isinstance(p, (int, float)):
            prices.append(p)
        elif p not in (None, "") and len(samples_bad_price) < 8:
            samples_bad_price.append(p)
        if isinstance(r.get("average_rating"), (int, float)):
            ratings.append(r["average_rating"])
        if isinstance(r.get("rating_number"), int):
            rating_counts.append(r["rating_number"])
        n_images.append(len(r.get("images") or []))
        title_len.append(len(r.get("title") or ""))
        asins[r.get("parent_asin")] += 1
        titles[(r.get("title") or "").strip().lower()] += 1

    print(f"records: {n}\n")
    print("field coverage (present / non-empty / types):")
    for k in sorted(present, key=lambda k: -present[k]):
        ne = present[k] - empty[k]
        print(f"  {k:18} {present[k]:6} {ne:6} ({ne / n:5.1%})  {dict(types[k])}")

    def pct(xs, qs=(0, 10, 50, 90, 99, 100)):
        s = sorted(xs)
        return {q: s[min(len(s) - 1, int(q / 100 * len(s)))] for q in qs}

    print(f"\nprice types: {dict(price_types)}")
    print(f"non-numeric price samples: {samples_bad_price}")
    if prices:
        print(f"price percentiles: {pct(prices)}  mean={statistics.mean(prices):.2f}")
        print(f"  price == 0: {sum(1 for p in prices if p == 0)}, > 1000: {sum(1 for p in prices if p > 1000)}")
    print(f"rating percentiles: {pct(ratings)}")
    print(f"rating_number percentiles: {pct(rating_counts)}")
    print(f"images per product: {pct(n_images)}  zero images: {n_images.count(0)}")
    print(f"title length: {pct(title_len)}  empty titles: {title_len.count(0)}")

    dup_asin = sum(c - 1 for c in asins.values() if c > 1)
    dup_title = sum(c - 1 for t, c in titles.items() if c > 1 and t)
    print(f"\nduplicate parent_asin rows: {dup_asin}; duplicate titles: {dup_title}")

    print(f"\nmain_category: {main_cat.most_common(15)}")
    print(f"categories depth: {sorted(cat_depth.items())}")
    print("top categories (first 2 levels):")
    for c, k in top_cat.most_common(30):
        print(f"  {k:6} {c}")
    print(f"\nstores: {len(stores)} distinct; top: {stores.most_common(10)}")
    print(f"\ndetails keys: {len(detail_keys)} distinct; top 50:")
    for k, c in detail_keys.most_common(50):
        print(f"  {c:6} ({c / n:5.1%})  {k}")


if __name__ == "__main__":
    main(sys.argv[1])
