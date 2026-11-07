"""Throwaway stand-in for crv's planned runtime tools, over the raw catalog. Exploration only.

The eval scripts (scripts/eval_*.py) import it to run DeepSeek through real conversations.
- search_products: hard filters (price, rating, brand, audience), keyword match on the title and the
  category path without its root, one card per product. With a colour or size, DeepSeek
  (reasoning none) labels the top 30 products, as in "Search flow" in docs/data-and-search.md.
- get_product, catalog_stats, similar_products (title TF-IDF within the same product type).
- Every Fireworks call is appended to data/tests/eval_cost.jsonl with its tokens and dollars. Calls
  stop once the total passes CRV_BUDGET (default $1.85).
"""

import html
import json
import math
import os
import re
import statistics
import sys
import threading
import time
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fw  # noqa: E402

# ------------------------------------------------------------------ Fireworks calls, with a ledger

PRICES = {  # USD per 1M tokens: input, cached input, output (fireworks.ai/pricing, 2026-11-07)
    fw.LLM: (0.30, 0.006, 1.20),
    fw.EMBED: (0.10, 0.10, 0.0),
    fw.RERANK: (0.10, 0.10, 0.0),  # not on the pricing page: assumed equal to the Qwen3 8B embedding price
}
LEDGER = os.environ.get("CRV_LEDGER", "data/tests/eval_cost.jsonl")
BUDGET = float(os.environ.get("CRV_BUDGET", "1.85"))
_lock = threading.Lock()
_spent = sum(json.loads(line)["usd"] for line in open(LEDGER)) if os.path.exists(LEDGER) else 0.0


def spent():
    return _spent


def _record(model, purpose, usage, secs):
    global _spent
    p_in, p_cached, p_out = PRICES[model]
    prompt = usage.get("prompt_tokens") or 0
    out = usage.get("completion_tokens") or 0
    cached = (usage.get("prompt_tokens_details") or {}).get("cached_tokens") or 0
    usd = ((prompt - cached) * p_in + cached * p_cached + out * p_out) / 1e6
    row = {"t": round(time.time(), 1), "model": model.split("/")[-1], "purpose": purpose, "prompt": prompt,
           "cached": cached, "out": out, "usd": usd, "secs": round(secs, 3)}
    with _lock:
        _spent += usd
        with open(LEDGER, "a") as f:
            f.write(json.dumps(row) + "\n")


def _guard():
    if _spent > BUDGET:
        raise RuntimeError(f"budget stop: ${_spent:.3f} spent, limit ${BUDGET}")


def chat(messages, purpose, tools=None, effort="low", max_tokens=3000, model=fw.LLM, **extra):
    """One chat completion. Returns (message, seconds, usage)."""
    _guard()
    body = {"model": model, "messages": messages, "max_tokens": max_tokens, "temperature": 0, **extra}
    if effort:
        body["reasoning_effort"] = effort
    if tools:
        body["tools"] = tools
    resp, secs = fw.post("/chat/completions", body)
    usage = resp.get("usage") or {}
    _record(model, purpose, usage, secs)
    return resp["choices"][0]["message"], secs, usage


def embed(texts, purpose, batch=64, **extra):
    """L2-normalised Qwen3 embeddings, logged to the ledger."""
    out = []
    for i in range(0, len(texts), batch):
        _guard()
        chunk = texts[i : i + batch]
        resp, secs = fw.post("/embeddings", {"model": fw.EMBED, "input": chunk, **extra})
        _record(fw.EMBED, purpose, resp.get("usage") or {"prompt_tokens": sum(map(len, chunk)) // 4}, secs)
        for item in sorted(resp["data"], key=lambda d: d["index"]):
            v = item["embedding"]
            n = math.sqrt(sum(x * x for x in v)) or 1.0
            out.append([x / n for x in v])
    return out


def rerank(query, docs, purpose):
    """Qwen3 reranker scores, in document order. Returns (scores, seconds)."""
    _guard()
    resp, secs = fw.post("/rerank", {"model": fw.RERANK, "query": query, "documents": docs, "return_documents": False})
    est = {"prompt_tokens": (len(query) * len(docs) + sum(map(len, docs))) // 4}
    _record(fw.RERANK, purpose, resp.get("usage") or est, secs)
    scores = [0.0] * len(docs)
    for item in resp.get("data") or resp.get("results") or []:
        scores[item["index"]] = item.get("relevance_score", item.get("score", 0.0))
    return scores, secs


# ------------------------------------------------------------------ catalog

NL = "Not listed"
MISSING = {"nan", "none", "null", "", "n/a"}
DEPT = {"womens": "women", "women": "women", "mens": "men", "men": "men", "boys": "boys", "girls": "girls",
        "baby": "baby", "baby-boys": "baby", "baby-girls": "baby", "unisex-baby": "baby",
        "unisex-adult": "unisex", "unisex-child": "unisex"}
STOP = set("a an the and or for with in on of to me show find i im want need looking some any my "
           "under over below above than price please".split())
SOFT_PROMPT = """The shopper asked for: {wants}.
For each product, label each asked constraint using ONLY the catalog value shown:
- "match": the value names it.
- "partial": the value names it as one of several (colour "Orange/Black" is partial for orange).
- "no": the value names something else.
- "unknown": the value is missing or doesn't say (a code like "203n", "Assorted", "Not listed").
Colour: a shade of the asked colour is a match (navy is a blue).
Size: a size matches only if the label itself names the asked size. For small: "S", "Small" and "US S" match;
"Medium", "Large" and "X-Small" don't. "One Size" is unknown.
Reply with JSON only, one entry per id: {{"<id>": {{{keys}}}}}
Products:
{rows}"""
LABEL_SCORE = {"match": 2, "partial": 1, "unknown": 0}


def clean(v):
    if isinstance(v, str):
        v = html.unescape(v).strip()
        return None if v.lower() in MISSING else v
    return v


def words(s):
    return re.findall(r"[a-z0-9]+", (s or "").lower())


def stem(w):
    if w.endswith("ies") and len(w) > 4:
        return w[:-3] + "y"
    if w.endswith("sses"):
        return w[:-2]
    if w.endswith("s") and not w.endswith("ss") and len(w) > 3:
        return w[:-1]
    return w


def stems(s):
    return {stem(w) for w in words(s)}


def query_stems(q):
    return list(dict.fromkeys(stem(w) for w in words(q) if w not in STOP))


SIZE_ALT = {"small": "s", "s": "small", "medium": "m", "m": "medium", "large": "l", "l": "large",
            "xlarge": "xl", "xl": "xlarge"}


def size_tokens(s):
    toks = set(words((s or "").replace("x-large", "xlarge").replace("x-small", "xsmall")))
    return {t for t in toks | {SIZE_ALT[t] for t in toks if t in SIZE_ALT} if len(t) > 1 or t.isdigit() or t in "sml"}


def _norm(s):
    return re.sub(r"[^a-z0-9 ]+", " ", (s or "").lower())


def _strip_variant(r):
    t = " " + _norm(r["title"]) + " "
    for v in (r["color"], r["size"]):
        v = _norm(v).strip()
        if len(v) > 1:
            t = t.replace(" " + v + " ", " ")
    return " ".join(t.split())


GROUP_KEYS = (  # the three grouping signals in data-and-search.md, "Grouping variants into products"
    lambda r: (_norm(r["title"]).strip(), _norm(r["store"])),
    lambda r: (_strip_variant(r), _norm(r["store"])),
    lambda r: (_norm(r["store"]), r["average_rating"], r["rating_number"], " ".join(_norm(r["title"]).split()[:4])),
)


class Catalog:
    def __init__(self, path):
        raw = [r for r in fw.records(path) if clean(r["title"])]
        parent = list(range(len(raw)))

        def find(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i

        for key in GROUP_KEYS:
            first = {}
            for i, r in enumerate(raw):
                k = key(r)
                if k in first:
                    parent[find(i)] = find(first[k])
                else:
                    first[k] = i
        self.variants = [self._variant(r) for r in raw]
        groups = defaultdict(list)
        for i in range(len(raw)):
            groups[find(i)].append(i)
        self.products, self.by_asin = {}, {}
        for idx in groups.values():
            lead = self.variants[idx[0]]
            pid = lead["asin"]
            vs = [self.variants[i] for i in idx]
            self.products[pid] = {"id": pid, "vs": vs, "lead": lead, "brand": lead["brand"], "type": lead["type"],
                                  "rating": lead["rating"], "reviews": max(v["reviews"] for v in vs),
                                  "image": lead["image"]}
            for v in vs:
                v["pid"] = pid
                self.by_asin[v["asin"]] = pid
        self._tfidf()

    @staticmethod
    def _variant(r):
        cats = r["categories"][1:]
        audience = next((c.lower() for c in cats[:1] if c in fw.AUDIENCE), None) or DEPT.get((clean(r["department"]) or "").lower())
        details = {k: clean(v) for k, v in (r.get("details") or {}).items() if isinstance(v, str)}
        details = {k: v for k, v in details.items() if v}
        material = clean(r.get("material")) or details.get("Material") or details.get("Fabric Type")
        image = fw.main_image(r) or next((i.get("large") for i in r["images"]), None)
        title = clean(r["title"])
        return {"asin": r["parent_asin"], "title": title, "brand": clean(r["store"]) or clean(r["brand"]),
                "price": r["price"], "list_price": r.get("original_price"), "rating": r["average_rating"],
                "reviews": r["rating_number"], "colour": clean(r["color"]), "size": clean(r["size"]),
                "material": material, "style": clean(r.get("style")), "path": cats, "type": fw.product_type(r),
                "audience": audience, "image": image, "features": [clean(f) for f in r["features"] if clean(f)],
                "description": [clean(d) for d in r["description"] if clean(d)], "details": details,
                "pop": (r["average_rating"] / 5) * math.log1p(r["rating_number"]) / math.log1p(40455),
                "t_st": stems(title), "p_st": stems(" ".join(cats))}

    def _tfidf(self):
        df = Counter()
        for p in self.products.values():
            df.update(p["lead"]["t_st"])
        n = len(self.products)
        self.by_type = defaultdict(list)
        for p in self.products.values():
            w = {s: math.log(n / df[s]) for s in p["lead"]["t_st"]}
            norm = math.sqrt(sum(x * x for x in w.values())) or 1.0
            p["tf"] = {s: x / norm for s, x in w.items()}
            self.by_type[p["type"]].append(p["id"])

    # -------------------------------------------------------------- shared pieces

    @staticmethod
    def _hard(v, a):
        p = v["price"]
        if a.get("max_price") is not None:
            mp = float(a["max_price"])
            if (p > mp) if a.get("max_price_inclusive") else (p >= mp):
                return False
        if a.get("min_price") is not None and p < float(a["min_price"]):
            return False
        if a.get("min_rating") is not None and v["rating"] < float(a["min_rating"]):
            return False
        if a.get("brand") and (v["brand"] or "").lower() != str(a["brand"]).strip().lower():
            return False
        aud = str(a.get("audience") or "").lower()
        if aud and v["audience"] not in (aud, None, "unisex"):
            return False
        return True

    @staticmethod
    def _kw(v, q):
        cov = score = 0
        for s in q:
            in_path, in_title = s in v["p_st"], s in v["t_st"]
            cov += in_path or in_title
            score += 2 * in_path + in_title
        return cov, score

    @staticmethod
    def _bonus(v, colour, size):
        b = 0
        if colour and stems(colour) & stems(v["colour"]):
            b += 1
        if size and size_tokens(size) & size_tokens(v["size"]):
            b += 1
        return b

    def card(self, v, labels=None):
        p = self.products[v["pid"]]
        colours = list(dict.fromkeys(x["colour"] for x in p["vs"] if x["colour"]))
        sizes = list(dict.fromkeys(x["size"] for x in p["vs"] if x["size"]))
        c = {"id": p["id"], "title": v["title"], "brand": v["brand"] or NL, "price": v["price"], "rating": v["rating"],
             "reviews": v["reviews"], "colour": v["colour"] or NL, "size": v["size"] or NL,
             "seen_in_colours": colours[:8], "seen_in_sizes": sizes[:10], "type": v["type"] or NL,
             "audience": v["audience"] or "unknown", "image": v["image"]}
        if labels:
            c["labels"] = labels
        return c

    # -------------------------------------------------------------- tools

    def search_products(self, query="", colour=None, size=None, _inject=None, **a):
        ranked, note = self.rank(query, colour, size, **a)
        out = {"total_matches": len(ranked)}
        if note:
            out["note"] = note
        if not (colour or size) or not ranked:
            out["products"] = [self.card(v) for v in ranked[:10]]
            return out
        short = ranked[:30]
        labels, secs = self.judge(short, colour, size)
        return self._apply_labels(out, short, labels, colour, size, secs)

    def rank(self, query="", colour=None, size=None, **a):
        """Hard filters, keyword match, one best variant per product. Returns (ranked lead variants, note)."""
        q = query_stems(query)
        hits, best_cov = [], 0
        for v in self.variants:
            if not self._hard(v, a):
                continue
            cov, sc = self._kw(v, q) if q else (0, 0)
            if cov or not q:
                hits.append((cov, sc, v))
                best_cov = max(best_cov, cov)
        best = {}
        for cov, sc, v in hits:
            if cov != best_cov:
                continue
            key = (self._bonus(v, colour, size), sc, v["pop"])  # colour/size text first, as in "Photos and colour"
            if v["pid"] not in best or key > best[v["pid"]][0]:
                best[v["pid"]] = (key, v)
        ranked = [v for _, v in sorted(best.values(), key=lambda kv: kv[0], reverse=True)]
        note = f"no product matched every query word; showing products matching {best_cov} of {len(q)}" if q and best_cov < len(q) else None
        return ranked, note

    def _apply_labels(self, out, short, labels, colour, size, secs):
        rows = []
        for i, v in enumerate(short):
            lab = {}
            for k, val in (("colour", colour), ("size", size)):
                if val:
                    got = str(labels.get(v["asin"], {}).get(k, "unknown")).lower()
                    lab[k] = {"label": got if got in ("match", "partial", "no", "unknown") else "unknown",
                              "value": v[k] or NL}
            if any(x["label"] == "no" for x in lab.values()):
                continue
            rows.append((-sum(LABEL_SCORE[x["label"]] for x in lab.values()), i, v, lab))
        rows.sort(key=lambda t: t[:2])
        shown = [self.card(v, lab) for _, _, v, lab in rows[:10]]
        counts = Counter(x["label"] for c in shown for x in c["labels"].values())
        out.update({"products": shown, "soft_check": {"checked": len(short), "not_matching_dropped": len(short) - len(rows),
                                                       "labels_shown": dict(counts)}})
        out["judge_secs"] = round(secs, 2)
        return out

    def judge(self, vs, colour, size):
        asked = [(k, val) for k, val in (("colour", colour), ("size", size)) if val]
        wants = ", ".join(f'{k} = "{val}"' for k, val in asked)
        keys = ", ".join(f'"{k}": "..."' for k, _ in asked)
        rows = "\n".join(json.dumps({"id": v["asin"], **{k: v[k] or NL for k, _ in asked}}) for v in vs)
        try:
            msg, secs, _ = chat([{"role": "user", "content": SOFT_PROMPT.format(wants=wants, keys=keys, rows=rows)}],
                                "soft_judge", effort="none", max_tokens=3000)
            return fw.parse_json(msg.get("content") or "") or {}, secs
        except RuntimeError:
            return {}, 0.0

    def get_product(self, id="", _inject=None, **_):
        pid = self.by_asin.get(str(id).strip())
        if pid is None:
            return {"error": f"no product with id {id}"}
        p = self.products[pid]
        lead = p["lead"]
        desc = " ".join(lead["description"])[:700]
        if _inject and pid in _inject:
            desc = _inject[pid] + " " + desc
        prices = [v["price"] for v in p["vs"]]
        return {"id": pid, "title": lead["title"], "brand": lead["brand"] or NL, "type": lead["type"] or NL,
                "category": " > ".join(lead["path"]), "audience": lead["audience"] or "unknown",
                "rating": lead["rating"], "reviews": p["reviews"], "price": lead["price"],
                "price_range": [min(prices), max(prices)], "variant_count": len(p["vs"]),
                "variants": [{"colour": v["colour"] or NL, "size": v["size"] or NL, "price": v["price"]} for v in p["vs"][:20]],
                "material": lead["material"] or NL, "style": lead["style"] or NL,
                "features": [f[:200] for f in lead["features"][:6]] or NL, "description": desc or NL,
                "details": {k: v[:100] for k, v in list(lead["details"].items())[:15]} or NL,
                "list_price": lead["list_price"] or NL}

    def catalog_stats(self, query=None, _inject=None, **a):
        q = query_stems(query or "")
        vs = [v for v in self.variants if self._hard(v, a) and (not q or self._kw(v, q)[0] == len(q))]
        filters = {k: val for k, val in {"query": query, **a}.items() if val not in (None, "")}
        if not vs:
            return {"products": 0, "listings": 0, "filters": filters, "note": "nothing matched these filters"}
        pids = list(dict.fromkeys(v["pid"] for v in vs))
        brands = Counter(self.products[p]["brand"] or NL for p in pids)
        types = Counter(self.products[p]["type"] or NL for p in pids)
        prices = [v["price"] for v in vs]
        out = {"products": len(pids), "listings": len(vs), "price_min": min(prices), "price_max": max(prices),
               "price_median": round(statistics.median(prices), 2),
               "average_rating": round(statistics.mean(self.products[p]["rating"] for p in pids), 2),
               "top_brands": brands.most_common(8), "top_types": types.most_common(8), "filters": filters}
        if a.get("audience"):
            out["listings_with_unknown_audience_included"] = sum(1 for v in vs if v["audience"] is None)
        return out

    def similar_products(self, id="", _inject=None, **_):
        pid = self.by_asin.get(str(id).strip())
        if pid is None:
            return {"error": f"no product with id {id}"}
        p = self.products[pid]
        pool = self.by_type.get(p["type"], [])
        if len(pool) < 7:
            parent = p["lead"]["path"][-2:-1]
            pool = [o for o in self.products if self.products[o]["lead"]["path"][-2:-1] == parent]
        scored = sorted(((sum(w * self.products[o]["tf"].get(s, 0) for s, w in p["tf"].items()), o)
                         for o in pool if o != pid), reverse=True)
        seen, out = {p["image"]}, []
        for _, o in scored:
            if self.products[o]["image"] in seen:
                continue
            seen.add(self.products[o]["image"])
            out.append(self.card(self.products[o]["lead"]))
            if len(out) == 6:
                break
        return {"similar_to": pid, "products": out}

    def call(self, name, args, inject=None):
        fn = {"search_products": self.search_products, "get_product": self.get_product,
              "catalog_stats": self.catalog_stats, "similar_products": self.similar_products}.get(name)
        if fn is None:
            return {"error": f"unknown tool {name}"}
        try:
            return fn(_inject=inject, **args)
        except (TypeError, ValueError) as e:
            return {"error": f"bad arguments: {e}"}


TOOLS = [
    {"type": "function", "function": {
        "name": "search_products",
        "description": "Search the catalog. Returns up to 10 products, one card per product, with the best-matching variant. "
                       "Colour and size are checked against each listing and labelled match, partial or unknown.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "The kind of product in plain words, without price, rating, brand, colour or size. Example: 'running shoes'."},
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
        "name": "get_product", "description": "All catalog fields for one product: variants, material, features, description and details.",
        "parameters": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}}},
    {"type": "function", "function": {
        "name": "catalog_stats",
        "description": "Counts (products and listings), price range, average rating, top brands and product types for a set of filters. "
                       "Use for questions about the whole catalog.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "Kind of product, optional."}, "brand": {"type": "string"},
            "audience": {"type": "string", "enum": ["women", "men", "boys", "girls", "baby"]},
            "min_price": {"type": "number"}, "max_price": {"type": "number"}, "min_rating": {"type": "number"}}}}},
    {"type": "function", "function": {
        "name": "similar_products", "description": "Up to 6 products like a given one, of the same kind.",
        "parameters": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}}},
]


def llm_view(result):
    """What the LLM sees: the tool result without image URLs, titles capped."""
    if isinstance(result, dict) and isinstance(result.get("products"), list):
        result = {**result, "products": [{k: (v[:110] if k == "title" else v) for k, v in c.items() if k != "image"}
                                          for c in result["products"]]}
    return result
