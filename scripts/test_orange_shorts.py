"""Test 2: find "orange running shorts in size small" using photo colours vs the colour text.

1. Candidates: running shorts (a shorts category or "shorts" in the title, plus running in the title
   or category path).
2. DeepSeek reads each main photo, without seeing the catalog colour, and lists the colours and what
   the item is. Results are cached in data/tests/photo_reads.jsonl for other tests.
3. DeepSeek judges each distinct size string: does it include small?
4. Compares matches found from the photo with matches found from the colour text.

Usage: FIREWORKS_API_KEY=... python3 -I scripts/test_orange_shorts.py data/raw/products.json
"""

import json
import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fw  # noqa: E402

CACHE = "data/tests/photo_reads.jsonl"
READ_PROMPT = """Look at this product photo.
Reply with JSON only: {"colours": ["colours of the product itself, main colour first, plain colour words"], "item": "what the item is, in 2-5 words"}"""
SIZE_PROMPT = """A shopper wants size small. For each size label below, answer whether that listing is size small
(S, Small, US S, "Medium/US S" counts as small; "X-Small" and "XX-Small" do not; "One Size" is "unknown").
Reply with JSON only, mapping each label exactly as given to "yes", "no" or "unknown".
Labels:
"""


def is_candidate(r):
    title = (r["title"] or "").lower()
    path = " > ".join(r["categories"]).lower()
    shorts = "shorts" in (fw.product_type(r) or "").lower() or re.search(r"\bshorts\b", title)
    running = re.search(r"\brun(ning)?\b", title) or "running" in path
    return bool(shorts and running and fw.main_image(r))


def read_photo(r):
    msg, secs, _ = fw.chat([{"role": "user", "content": [{"type": "text", "text": READ_PROMPT}, fw.image_part(fw.main_image(r))]}],
                           max_tokens=2000)
    out = fw.parse_json(msg.get("content") or "") or {}
    return {"asin": r["parent_asin"], "image": fw.main_image(r), "colours": out.get("colours", []),
            "item": out.get("item"), "secs": round(secs, 2)}


def main(path):
    cands = [r for r in fw.records(path) if is_candidate(r)]
    print(f"candidates (running shorts): {len(cands)}")

    cached = {}
    if os.path.exists(CACHE):
        cached = {d["asin"]: d for d in map(json.loads, open(CACHE))}
    todo = [r for r in cands if r["parent_asin"] not in cached]
    with ThreadPoolExecutor(8) as pool:
        for d in pool.map(read_photo, todo):
            cached[d["asin"]] = d
            with open(CACHE, "a") as f:
                f.write(json.dumps(d) + "\n")
    secs = sorted(cached[r["parent_asin"]]["secs"] for r in cands)
    print(f"photos read: {len(todo)} new, {len(cands) - len(todo)} cached; median {secs[len(secs) // 2]:.1f}s")

    labels = sorted({r["size"] for r in cands})
    msg, s, _ = fw.chat([{"role": "user", "content": SIZE_PROMPT + "\n".join(json.dumps(l) for l in labels)}], max_tokens=8000)
    size_ok = fw.parse_json(msg.get("content") or "") or {}
    print(f"size labels judged: {len(size_ok)} of {len(labels)} in {s:.1f}s; small = {sorted(l for l, v in size_ok.items() if v == 'yes')}")

    def photo_orange(r):
        cols = [c.lower() for c in cached[r["parent_asin"]]["colours"]]
        if cols and "orange" in cols[0]:
            return "match"
        return "partial" if any("orange" in c for c in cols) else "no"

    small = [r for r in cands if size_ok.get(r["size"]) == "yes"]
    by_photo = [r for r in small if photo_orange(r) != "no"]
    by_text = [r for r in small if "orange" in r["color"].lower()]
    print(f"\nsmall: {len(small)}; orange by photo: {len(by_photo)}; orange by colour text: {len(by_text)}")
    for label, rows in (("photo", by_photo), ("text only", [r for r in by_text if r not in by_photo])):
        print(f"\n{label}:")
        for r in rows:
            d = cached[r["parent_asin"]]
            print(f"  {r['parent_asin']} ${r['price']:.2f} size={r['size']!r} text={r['color']!r} photo={d['colours']} item={d['item']!r} [{photo_orange(r)}]")
            print(f"      {r['title'][:70]} {d['image']}")


if __name__ == "__main__":
    main(sys.argv[1])
