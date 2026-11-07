"""Small helpers for the test scripts: read the catalog, and call Fireworks with retries.

The API key comes from the FIREWORKS_API_KEY environment variable and is never printed.
"""

import base64
import json
import math
import os
import time
import urllib.error
import urllib.request

BASE = "https://api.fireworks.ai/inference/v1"
LLM = "accounts/fireworks/models/deepseek-v4p1-flash"
EMBED = "fireworks/qwen3-embedding-8b"
RERANK = "fireworks/qwen3-reranker-8b"
AUDIENCE = {"Women", "Men", "Boys", "Girls", "Baby"}
RETRY_CODES = {429, 500, 502, 503, 504}


def records(path):
    """Yield catalog records from the one-record-per-line JSON array."""
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line not in ("[", "]", ""):
                yield json.loads(line)


def main_image(r):
    return next((i["large"] for i in r["images"] if i.get("variant") == "MAIN"), None)


def product_type(r):
    """Deepest category node that is not an audience word."""
    for node in reversed(r["categories"][1:]):
        if node not in AUDIENCE:
            return node
    return None


def post(path, body, tries=4, timeout=180):
    """POST JSON to Fireworks. Retries server errors and rate limits. Returns (json, seconds)."""
    data = json.dumps(body).encode()
    headers = {"Authorization": f"Bearer {os.environ['FIREWORKS_API_KEY']}", "Content-Type": "application/json"}
    for attempt in range(tries):
        start = time.perf_counter()
        try:
            req = urllib.request.Request(BASE + path, data=data, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.load(resp), time.perf_counter() - start
        except urllib.error.HTTPError as e:
            detail = e.read()[:300].decode(errors="replace")
            if e.code in RETRY_CODES and attempt < tries - 1:
                time.sleep(2**attempt)
                continue
            raise RuntimeError(f"HTTP {e.code}: {detail}") from None
        except (urllib.error.URLError, TimeoutError):
            if attempt < tries - 1:
                time.sleep(2**attempt)
                continue
            raise


def chat(messages, model=LLM, tools=None, max_tokens=2000, temperature=0, **extra):
    """One chat completion. Returns (message dict, seconds, usage dict)."""
    body = {"model": model, "messages": messages, "max_tokens": max_tokens, "temperature": temperature, **extra}
    if tools:
        body["tools"] = tools
    resp, secs = post("/chat/completions", body)
    return resp["choices"][0]["message"], secs, resp.get("usage", {})


def image_part(url):
    """Download an image and wrap it as a base64 chat content part."""
    req = urllib.request.Request(url, headers={"User-Agent": "crv-test"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = resp.read()
    return {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(data).decode()}}


def embed(texts, model=EMBED, batch=64, **extra):
    """Embed texts in batches. Returns L2-normalised vectors (Qwen3 embeddings are not normalised)."""
    out = []
    for i in range(0, len(texts), batch):
        resp, _ = post("/embeddings", {"model": model, "input": texts[i : i + batch], **extra})
        for item in sorted(resp["data"], key=lambda d: d["index"]):
            v = item["embedding"]
            n = math.sqrt(sum(x * x for x in v)) or 1.0
            out.append([x / n for x in v])
    return out


def rerank(query, documents, model=RERANK, top_n=None):
    """Rerank documents for a query. Returns (raw response, seconds)."""
    body = {"model": model, "query": query, "documents": documents, "return_documents": False}
    if top_n:
        body["top_n"] = top_n
    return post("/rerank", body)


def parse_json(text):
    """Pull the first JSON object or array out of a model reply."""
    if not text:
        return None
    starts = [i for i in (text.find("{"), text.find("[")) if i >= 0]
    if not starts:
        return None
    start = min(starts)
    end = max(text.rfind("}"), text.rfind("]"))
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None
