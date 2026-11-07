"""Test 2: does matching a request to category types by qwen3-embedding-8b beat keywords?

A "type" is a category path without the root and without audience nodes, for example
"Shoes > Athletic > Running > Road Running". For each request:
- keyword: types whose path contains every request word (stemmed);
- embedding: the top-k types by cosine similarity to the request (k = 1, 3, 5, 10).
Gold: DeepSeek (reasoning low) labels every candidate type yes / partly / no for the request; I
spot-check the labels in the printout. Metrics, counted in products:
- recall: share of products in "yes" types that the filter keeps;
- precision: share of kept products that sit in "yes" types;
- title recall: share of products whose title has every request word that the filter keeps
  (catches misfiled products that a type filter would drop).

Usage: FIREWORKS_API_KEY=... python3 -I scripts/eval_types.py data/raw/products.json
"""

import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_tools as ev  # noqa: E402
import fw  # noqa: E402

OUT = "data/tests/eval_types.json"
CACHE = "data/tests/eval_types_vectors.json"
QUERIES = ["running shoes", "shorts", "running shorts", "rain jacket", "backpack", "sneakers", "trainers", "joggers",
           "flip flops", "hoodie", "purse", "tee shirt", "swimsuit", "hiking boots", "winter hat", "sports bra"]
INSTRUCT = "Instruct: Given a shopper's request, retrieve the product category that matches it\nQuery: "
KS = (1, 3, 5, 10)
LABEL_PROMPT = """A shopper asks for: "{q}".
For each catalog category path below, say whether its products are the kind of product the shopper asks for:
"yes" (this category is that kind of product), "partly" (it mixes that kind with other things, or is a broader group
that often contains it), or "no".
Reply with JSON only: {{"<number>": "yes" | "partly" | "no", ...}}
Categories:
{rows}"""


def type_key(v):
    return " > ".join(n for n in v["path"] if n not in fw.AUDIENCE) or "(none)"


def main(path):
    cat = ev.Catalog(path)
    prods = list(cat.products.values())
    tkey = {p["id"]: type_key(p["lead"]) for p in prods}
    size = Counter(tkey.values())
    keys = sorted(size)
    print(f"{len(keys)} types over {len(prods)} products")

    cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}
    todo = [k for k in keys if k not in cache]
    for k, vec in zip(todo, ev.embed(todo, "types_embed") if todo else []):
        cache[k] = vec
    qtodo = [q for q in QUERIES if "Q:" + q not in cache]
    for q, vec in zip(qtodo, ev.embed([INSTRUCT + q for q in qtodo], "types_embed") if qtodo else []):
        cache["Q:" + q] = vec
    json.dump(cache, open(CACHE, "w"))

    results = {}
    for q in QUERIES:
        qv = cache["Q:" + q]
        sims = sorted(((sum(a * b for a, b in zip(qv, cache[k])), k) for k in keys), reverse=True)
        emb_rank = [k for _, k in sims]
        qs = set(ev.query_stems(q))
        kw = {k for k in keys if qs <= ev.stems(k)}
        title_hits = [p for p in prods if qs <= p["lead"]["t_st"]]
        title_types = [k for k, _ in Counter(tkey[p["id"]] for p in title_hits).most_common(15)]
        cands = list(dict.fromkeys(emb_rank[:30] + sorted(kw) + title_types))
        rows = "\n".join(f"{i + 1}: {k}" for i, k in enumerate(cands))
        msg, secs, _ = ev.chat([{"role": "user", "content": LABEL_PROMPT.format(q=q, rows=rows)}], "types_gold",
                               effort="low", max_tokens=8000)
        raw = fw.parse_json(msg.get("content") or "") or {}
        gold = {k: str(raw.get(str(i + 1), "no")).lower() for i, k in enumerate(cands)}
        yes = {k for k, g in gold.items() if g == "yes"}
        yes_n = sum(size[k] for k in yes)
        title_ids = {p["id"] for p in title_hits}

        def score(kept):
            kept_n = sum(size[k] for k in kept)
            hit = sum(size[k] for k in kept & yes)
            t_kept = sum(1 for p in title_hits if tkey[p["id"]] in kept)
            return {"types": len(kept), "kept_products": kept_n,
                    "recall": round(hit / yes_n, 3) if yes_n else None,
                    "precision": round(hit / kept_n, 3) if kept_n else None,
                    "title_recall": round(t_kept / len(title_ids), 3) if title_ids else None}

        res = {"gold_yes_types": len(yes), "gold_yes_products": yes_n, "title_products": len(title_ids),
               "keyword": score(kw), **{f"emb@{k}": score(set(emb_rank[:k])) for k in KS},
               "top5": [(k, gold.get(k, "?"), size[k]) for k in emb_rank[:5]],
               "keyword_types": [(k, gold.get(k, "?"), size[k]) for k in sorted(kw, key=lambda k: -size[k])[:6]],
               "yes_types": sorted(yes, key=lambda k: -size[k])[:12]}
        results[q] = res
        print(f"\n== {q!r}: {len(yes)} yes types ({yes_n} products); {len(title_ids)} products with every word in the title")
        for m in ("keyword", *[f"emb@{k}" for k in KS]):
            s = res[m]
            print(f"   {m:8} types {s['types']:3} kept {s['kept_products']:5}  recall {s['recall']}  precision {s['precision']}  title recall {s['title_recall']}")
        print("   emb top5:", [(k[-45:], g, n) for k, g, n in res["top5"]])
        print("   yes types:", [k[-40:] for k in res["yes_types"]])

    def avg(m, f):
        xs = [r[m][f] for r in results.values() if r[m][f] is not None]
        return round(sum(xs) / len(xs), 3) if xs else None

    table = {m: {f: avg(m, f) for f in ("recall", "precision", "title_recall")} for m in ("keyword", *[f"emb@{k}" for k in KS])}
    print("\nmean over requests:")
    for m, s in table.items():
        print(f"   {m:8} recall {s['recall']}  precision {s['precision']}  title recall {s['title_recall']}")
    print(f"spent ${ev.spent():.3f}")
    json.dump({"per_query": results, "mean": table}, open(OUT, "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
