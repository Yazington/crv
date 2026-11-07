"""Runtime test: DeepSeek labels a 30-product shortlist against soft constraints (colour, size).

Usage: FIREWORKS_API_KEY=... python3 -I scripts/test_judge.py data/raw/products.json [effort]
"""
import json, os, random, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fw  # noqa: E402
from test_orange_shorts import is_candidate  # noqa: E402

PROMPT = """The shopper wants: colour = "{colour}", size = "{size}".
For each product, label each constraint using ONLY the catalog values given:
"match", "partial" (e.g. the colour is one of several colours), "no", or "unknown" (value missing or not a real size/colour).
Size "S", "Small", "US S" or "Medium/US S" count as small. "X-Small" does not. "One Size" is "unknown".
Reply with JSON only: {{"<id>": {{"colour": "...", "size": "..."}}, ...}}
Products:
{rows}"""

def main(path, effort="low"):
    rng = random.Random(3)
    cands = [r for r in fw.records(path) if is_candidate(r)]
    orange = [r for r in cands if "orange" in r["color"].lower()]
    pick = orange + rng.sample([r for r in cands if r not in orange], 30 - len(orange))
    rows = "\n".join(json.dumps({"id": r["parent_asin"], "colour": r["color"], "size": r["size"]}) for r in pick)
    extra = {} if effort == "default" else {"reasoning_effort": effort}
    t = time.perf_counter()
    msg, secs, usage = fw.chat([{"role": "user", "content": PROMPT.format(colour="orange", size="small", rows=rows)}], max_tokens=6000, **extra)
    labels = fw.parse_json(msg.get("content") or "") or {}
    print(f"effort={effort}: {secs:.1f}s, output tokens {usage.get('completion_tokens')} (thinking {(usage.get('completion_tokens_details') or {}).get('reasoning_tokens')}), labelled {len(labels)}/{len(pick)}")
    for r in pick:
        l = labels.get(r["parent_asin"], {})
        print(f"  {r['color'][:26]:26} {l.get('colour','-'):8} | {r['size'][:22]:22} {l.get('size','-')}")

if __name__ == "__main__":
    main(*sys.argv[1:])
