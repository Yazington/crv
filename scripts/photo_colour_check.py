"""Ask a Fireworks vision model whether each product photo shows the colour the catalog lists.

Samples variants whose colour text names a basic colour, plus variants that share a photo with a
differently coloured variant. Writes one JSON line per variant to the output file.

Usage:
  FIREWORKS_API_KEY=... python3 -I scripts/photo_colour_check.py data/raw/products.json OUT.jsonl \
      [--model <fireworks model, default DeepSeek V4.1 Flash>] [--random 60] [--shared 20] [--workers 6]
"""

import argparse
import json
import os
import random
import re
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fw  # noqa: E402

# Only used to pick a sample where the colour text is checkable; not used by the product.
BASIC = r"\b(black|white|blue|navy|red|green|pink|grey|gray|brown|silver|gold|purple|orange|yellow|beige|khaki|tan)\b"

PROMPT = """You are checking a product catalog. The catalog lists this product's colour as: "{colour}".
Product title: "{title}".
Look at the photo. Does the product in the photo show that colour?
Reply with JSON only: {{"verdict": "match" | "partial" | "mismatch" | "cant_tell", "seen": ["main colours you see on the product"], "why": "a few words"}}
"partial" means the listed colour is there but is not the main colour, or only some listed colours are there."""

records, main_image = fw.records, fw.main_image


def sample(path, n_random, n_shared, seed=7):
    rows = [r for r in records(path) if main_image(r) and re.search(BASIC, r["color"].lower())]
    fam = defaultdict(list)
    for r in rows:
        fam[((r["title"] or "").strip().lower(), (r["store"] or "").lower())].append(r)
    shared = []
    for g in fam.values():
        by_img = defaultdict(list)
        for r in g:
            by_img[main_image(r)].append(r)
        for group in by_img.values():
            basics = {m for r in group for m in re.findall(BASIC, r["color"].lower())}
            if len(group) > 1 and len(basics) > 1:
                shared.extend(group)
    rng = random.Random(seed)
    picked_shared = rng.sample(shared, min(n_shared, len(shared)))
    ids = {r["parent_asin"] for r in picked_shared}
    picked_random = rng.sample([r for r in rows if r["parent_asin"] not in ids], n_random)
    return [("shared", r) for r in picked_shared] + [("random", r) for r in picked_random]


def ask(model, r):
    content = [{"type": "text", "text": PROMPT.format(colour=r["color"], title=(r["title"] or "")[:150])},
               fw.image_part(main_image(r))]
    msg, secs, usage = fw.chat([{"role": "user", "content": content}], model=model, max_tokens=4000)
    out = fw.parse_json(msg.get("content") or "")
    if not isinstance(out, dict):
        out = {"verdict": "unparsed", "raw": (msg.get("content") or "")[:200]}
    return out, secs, usage


def main():
    p = argparse.ArgumentParser()
    p.add_argument("catalog")
    p.add_argument("out")
    p.add_argument("--model", default=fw.LLM)
    p.add_argument("--random", type=int, default=60)
    p.add_argument("--shared", type=int, default=20)
    p.add_argument("--workers", type=int, default=6)
    a = p.parse_args()
    items = sample(a.catalog, a.random, a.shared)

    def run(item):
        kind, r = item
        try:
            out, secs, usage = ask(a.model, r)
        except Exception as e:  # keep going; record the failure
            out, secs, usage = {"verdict": "error", "why": str(e)[:200]}, 0, {}
        return {"kind": kind, "asin": r["parent_asin"], "title": (r["title"] or "")[:80], "colour": r["color"],
                "image": main_image(r), "secs": round(secs, 2), "usage": usage, **out}

    with ThreadPoolExecutor(a.workers) as pool, open(a.out, "w") as f:
        results = list(pool.map(run, items))
        for row in results:
            f.write(json.dumps(row) + "\n")
    for kind in ("random", "shared"):
        rs = [r for r in results if r["kind"] == kind]
        print(f"{kind}: {len(rs)} checked -> {dict(Counter(r['verdict'] for r in rs))}")
    secs = sorted(r["secs"] for r in results if r["secs"])
    if secs:
        print(f"latency per photo: median {secs[len(secs) // 2]:.1f}s, max {secs[-1]:.1f}s")
    for r in results:
        if r["verdict"] in ("mismatch", "error", "unparsed"):
            print(f"  [{r['kind']}] {r['verdict']}: listed {r['colour']!r}, seen {r.get('seen')} — {r['title'][:50]} {r['image']}")


if __name__ == "__main__":
    main()
