"""Runtime test: does DeepSeek pick the right tool arguments, and how fast is it?

Runs the brief's requests and follow-ups against DeepSeek with the planned tools. Tool results are
real catalog rows, served from the raw file. Checks each tool call's arguments automatically.

Usage: FIREWORKS_API_KEY=... python3 -I scripts/test_tool_calling.py data/raw/products.json [effort ...]
  effort: default | low | medium | high (DeepSeek reasoning_effort). Default: default low
"""

import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fw  # noqa: E402

SYSTEM = """You are the shopping assistant for an Amazon Fashion catalog (clothing, shoes, jewelry, luggage, outdoor).
Rules:
- Use the tools. Only mention products, prices, colours and sizes that a tool returned in this conversation. Never invent facts.
- Call search_products with ALL of the shopper's current constraints. Keep earlier constraints unless the shopper changes or removes them.
- "Anything cheaper?" means max_price just below the cheapest product you last showed.
- If a request is broad and one answer would change the results a lot, ask ONE short question with 2-4 options. You may search first.
- If the catalog doesn't have something, say so plainly.
- At most 5 tool calls per turn. Keep replies short."""

TOOLS = [
    {"type": "function", "function": {
        "name": "search_products",
        "description": "Search the catalog. Returns up to 10 products, one card per product, with the best-matching variant.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "What the shopper wants in plain words, without price, rating or brand. Example: 'running shoes'."},
            "max_price": {"type": "number"},
            "max_price_inclusive": {"type": "boolean", "description": "true for 'up to' or 'at most'; false for 'under' or 'less than'."},
            "min_price": {"type": "number"},
            "min_rating": {"type": "number"},
            "brand": {"type": "string"},
            "audience": {"type": "string", "enum": ["women", "men", "boys", "girls", "baby"]},
            "colour": {"type": "string"},
            "size": {"type": "string"},
        }, "required": ["query"]}}},
    {"type": "function", "function": {
        "name": "get_product", "description": "All catalog fields for one product.",
        "parameters": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}}},
    {"type": "function", "function": {
        "name": "catalog_stats", "description": "Counts, price range and top brands for a set of filters. Use for questions about the whole catalog.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string"}, "brand": {"type": "string"},
            "audience": {"type": "string", "enum": ["women", "men", "boys", "girls", "baby"]}}}}},
    {"type": "function", "function": {
        "name": "similar_products", "description": "Products similar to a given product.",
        "parameters": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}}},
]


def card(r):
    return {"id": r["parent_asin"], "title": r["title"][:90], "price": r["price"], "rating": r["average_rating"],
            "reviews": r["rating_number"], "colour": r["color"], "size": r["size"], "brand": r["store"],
            "type": fw.product_type(r)}


def load(path):
    rows = list(fw.records(path))
    shoes = sorted([r for r in rows if r["categories"][-1] in ("Road Running", "Trail Running") and r["price"] < 120],
                   key=lambda r: -r["rating_number"])[:5]
    shorts = [r for r in rows if r["parent_asin"] in ("B07DJZZL65", "B01IPP5VF0", "B071LQ5HSB")]
    return {"shoes": [card(r) for r in shoes], "shorts": [card(r) for r in shorts]}


def fake_tool(name, args, data, scenario):
    if name == "catalog_stats":
        if (args.get("brand") or "").lower() == "nike":
            return {"products": 777, "listings": 994, "price_min": 9.28, "price_max": 446.25}
        return {"products": 0, "listings": 0}
    if name == "search_products":
        q = (args.get("query") or "").lower()
        if "laptop" in q and "bag" not in q and "backpack" not in q:
            return {"products": [], "note": "no products matched"}
        return {"products": data[scenario] if scenario else []}
    return {"error": "not available in this test"}


def run_turn(messages, data, scenario, extra, log):
    """Run one shopper turn to completion. Returns the first tool call's (name, args) and the final text."""
    first = None
    for _ in range(6):
        msg, secs, usage = fw.chat(messages, tools=TOOLS, max_tokens=4000, **extra)
        log.append((secs, usage.get("completion_tokens", 0)))
        keep = {"role": "assistant", "content": msg.get("content") or ""}
        if msg.get("tool_calls"):
            keep["tool_calls"] = msg["tool_calls"]
        if msg.get("reasoning_content"):
            keep["reasoning_content"] = msg["reasoning_content"]
        messages.append(keep)
        if not msg.get("tool_calls"):
            return first, msg.get("content") or ""
        for tc in msg["tool_calls"]:
            args = json.loads(tc["function"]["arguments"] or "{}")
            if first is None:
                first = (tc["function"]["name"], args)
            result = fake_tool(tc["function"]["name"], args, data, scenario)
            messages.append({"role": "tool", "tool_call_id": tc["id"], "content": json.dumps(result)})
    return first, "(hit the loop limit)"


def has(s, *words):
    return all(w in (s or "").lower() for w in words)


def cases(data):
    cheapest = min(p["price"] for p in data["shoes"])
    return [
        ("running shoes under $120", [("Show me running shoes under $120.", "shoes")],
         lambda c, t: c and c[0] == "search_products" and c[1].get("max_price") == 120 and not c[1].get("max_price_inclusive", False)
         and has(c[1].get("query"), "run") and has(c[1].get("query"), "sho")),
        ("orange running shorts, small", [("I'm looking for orange running shorts in size small.", "shorts")],
         lambda c, t: c and c[0] == "search_products" and has(c[1].get("colour"), "orange")
         and re.search(r"\b(s|small)\b", (c[1].get("size") or "").lower()) and has(c[1].get("query"), "short")),
        ("then: anything cheaper?", [("Show me running shoes under $120.", "shoes"), ("Anything cheaper?", "shoes")],
         lambda c, t: c and c[0] == "search_products" and (c[1].get("max_price") or 999) <= cheapest
         and has(c[1].get("query"), "run")),
        ("then: only blue ones", [("I'm looking for orange running shorts in size small.", "shorts"), ("Only show blue ones.", "shorts")],
         lambda c, t: c and c[0] == "search_products" and has(c[1].get("colour"), "blue") and not has(c[1].get("colour"), "orange")
         and re.search(r"\b(s|small)\b", (c[1].get("size") or "").lower()) and has(c[1].get("query"), "short")),
        ("how many Nike products?", [("How many Nike products do you have?", None)],
         lambda c, t: c and c[0] == "catalog_stats" and has(c[1].get("brand"), "nike") and "777" in t),
        ("women's rain jacket, 4+ stars, under $80", [("Women's rain jacket, 4 stars and up, under $80", "shoes")],
         lambda c, t: c and c[0] == "search_products" and c[1].get("audience") == "women" and c[1].get("min_rating") == 4
         and c[1].get("max_price") == 80),
        ("broad: I need shoes", [("I need shoes", "shoes")], lambda c, t: "?" in t),
        ("not in catalog: laptops", [("Do you sell laptops?", None)],
         lambda c, t: bool(re.search(r"\b(no|not|don't|doesn't|isn't|aren't)\b", t.lower()))),
    ]


def main(path, efforts):
    data = load(path)
    for effort in efforts:
        extra = {} if effort == "default" else {"reasoning_effort": effort}
        log, passed = [], 0
        print(f"\n== reasoning_effort: {effort}")
        for name, turns, check in cases(data):
            messages = [{"role": "system", "content": SYSTEM}]
            call, text = None, ""
            try:
                for user, scenario in turns:
                    messages.append({"role": "user", "content": user})
                    call, text = run_turn(messages, data, scenario, extra, log)
                ok = bool(check(call, text))
            except Exception as e:
                ok, text = False, f"error: {e}"
            passed += ok
            print(f"  {'PASS' if ok else 'FAIL'}  {name:40} call={call}")
            if not ok:
                print(f"        reply: {re.sub(r'\\s+', ' ', text)[:160]}")
        secs = sorted(s for s, _ in log)
        toks = [t for _, t in log]
        print(f"  passed {passed}/{len(cases(data))}; LLM calls {len(log)}; latency median {statistics.median(secs):.1f}s, "
              f"p90 {secs[int(0.9 * (len(secs) - 1))]:.1f}s; output tokens median {statistics.median(toks):.0f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:] or ["default", "low"])
