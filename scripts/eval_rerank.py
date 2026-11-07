"""Test 3: qwen3-reranker-8b versus the DeepSeek judge on the same 30-product shortlists.

Shortlists come from the stand-in search (eval_tools.Catalog.rank), the same top 30 the judge sees.
Reference labels (colour, size, kind) come from plain rules written for these six requests, and every
disagreement with the judge or the reranker is printed for review by hand.
Compared, 3 repeats each:
- judge30: DeepSeek, reasoning none, labels all 30 (the planned runtime step);
- judge10: the same on the top 10 only (latency);
- rerank: one reranker call, query = the whole request, documents = title + colour + size;
- rerank per constraint: query "colour: orange", documents "colour: <value>"; threshold picked on the
  other five requests (leave one out).
Writes data/tests/eval_rerank.json.

Usage: FIREWORKS_API_KEY=... python3 -I scripts/eval_rerank.py data/raw/products.json
"""

import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_tools as ev  # noqa: E402

OUT = "data/tests/eval_rerank.json"
REPEATS = 3
COLOURS = {"orange": r"orange|tangerine", "blue": r"blue|navy|teal|turquoise|aqua|cobalt|indigo|azure|sapphire",
           "red": r"\bred\b|burgundy|maroon|wine|crimson|scarlet|cherry|ruby", "white": r"white", "black": r"black"}
BASIC = (r"black|white|blue|navy|red|green|pink|grey|gray|brown|silver|gold|purple|orange|yellow|beige|khaki|tan|"
         r"multi|teal|burgundy|maroon|ivory|cream|olive|charcoal|coral|camo|leopard|floral|print|clear|nude|heather|"
         r"rose|wine|mint|lavender|violet|aqua|turquoise|indigo|denim|rust|mustard|copper|bronze|plaid|stripe")
SIZES = {"small": (r"(^|[^a-z-])(s|small|sm)([^a-z]|$)", r"x-?small|\bxs\b|xx"),
         "large": (r"(^|[^a-z-])(l|large|lg)([^a-z]|$)", r"x-?large|\bxl\b|xx|2x|3x"),
         "medium": (r"(^|[^a-z-])(m|medium|med)([^a-z]|$)", r"^$"),
         "8": (r"(^|[^\d.])8([^\d.]|$)", r"^$"),
         "x-large": (r"x-?large|(^|[^a-z0-9])xl([^a-z]|$)", r"xx|2x|3x|xxl")}
CASES = [  # query, colour, size, hard filters, kind rule on title + type
    ("running shorts", "orange", "small", {}, r"\bshorts\b"),
    ("t-shirt", "blue", "large", {"audience": "men"}, r"t-?shirt|\btee\b"),
    ("dress", "red", "medium", {"audience": "women"}, r"\bdress\b|dresses"),
    ("sneakers", "white", "8", {"audience": "women"}, r"sneaker|trainer|running shoe|athletic shoe|skate shoe|tennis shoe|walking shoe"),
    ("hoodie", "black", "x-large", {}, r"hoodie|hooded|sweatshirt"),
    ("running shoes", "blue", None, {}, r"(running|trail|road).*(shoe|sneaker|runner)|(shoe|sneaker).*running|\brunner\b"),
]


def gold_colour(text, colour):
    t = (text or "").lower()
    if not t:
        return "unknown"
    if re.search(COLOURS[colour], t):
        rest = re.sub(COLOURS[colour], " ", t)
        return "partial" if re.search(BASIC, rest) else "match"
    return "no" if re.search(BASIC, t) else "unknown"


def gold_size(text, size):
    t = (text or "").lower()
    if not t or "one size" in t or t in ("standard", "os"):
        return "unknown"
    yes, notp = SIZES[size]
    return "match" if re.search(yes, t) and not re.search(notp, t) else "no"


def fits(labels):
    return all(v in ("match", "partial") for v in labels.values())


def auc(scores, truth):
    pos = [s for s, y in zip(scores, truth) if y]
    neg = [s for s, y in zip(scores, truth) if not y]
    if not pos or not neg:
        return None
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return round(wins / (len(pos) * len(neg)), 3)


def p_at(order, good, k=10):
    top = order[:k]
    return round(sum(good[i] for i in top) / len(top), 3) if top else None


def main(path):
    cat = ev.Catalog(path)
    report, per_constraint = [], {"colour": [], "size": []}
    for query, colour, size, hard, kind_re in CASES:
        short = cat.rank(query, colour, size, **hard)[0][:30]
        asked = {k: v for k, v in (("colour", colour), ("size", size)) if v}
        gold = [{k: (gold_colour(v["colour"], colour) if k == "colour" else gold_size(v["size"], size)) for k in asked} for v in short]
        kind = [bool(re.search(kind_re, (v["title"] + " " + (v["type"] or "")).lower())) for v in short]
        fit = [fits(g) for g in gold]
        good = [f and k for f, k in zip(fit, kind)]

        judge30, judge10 = [], []
        for _ in range(REPEATS):
            lab, secs = cat.judge(short, colour, size)
            judge30.append((lab, secs))
            judge10.append(cat.judge(short[:10], colour, size)[1])
        lab = judge30[0][0]
        pred = [{k: str(lab.get(v["asin"], {}).get(k, "unknown")).lower() for k in asked} for v in short]
        stable = sum(all(str(r[0].get(v["asin"], {})) == str(lab.get(v["asin"], {})) for r in judge30) for v in short)
        j_order = sorted((i for i in range(len(short)) if not any(x == "no" for x in pred[i].values())),
                         key=lambda i: (-sum(ev.LABEL_SCORE.get(x, 0) for x in pred[i].values()), i))

        req = f"{colour} {query}" + (f", size {size}" if size else "")
        docs = [f"{v['title']} | colour: {v['colour'] or 'not listed'} | size: {v['size'] or 'not listed'}" for v in short]
        rr = [ev.rerank(req, docs, "rerank_test") for _ in range(REPEATS)]
        scores = rr[0][0]
        r_order = sorted(range(len(short)), key=lambda i: -scores[i])
        for k, val in asked.items():
            cs, secs = ev.rerank(f"{k}: {val}", [f"{k}: {v[k] or 'not listed'}" for v in short], "rerank_test")
            per_constraint[k].append({"case": query, "scores": cs, "truth": [g[k] in ("match", "partial") for g in gold], "secs": secs})

        acc = {k: round(sum(p[k] == g[k] for p, g in zip(pred, gold)) / len(short), 3) for k in asked}
        bin_acc = round(sum(fits(p) == f for p, f in zip(pred, fit)) / len(short), 3)
        row = {"case": req, "n": len(short), "gold_fit": sum(fit), "gold_fit_and_kind": sum(good),
               "judge_label_accuracy": acc, "judge_fit_accuracy": bin_acc, "judge_stable_items": f"{stable}/{len(short)}",
               "judge30_secs": [round(s, 2) for _, s in judge30], "judge10_secs": [round(s, 2) for s in judge10],
               "rerank_secs": [round(s, 2) for _, s in rr], "rerank_auc_fit": auc(scores, fit), "rerank_auc_fit_kind": auc(scores, good),
               "p10_fit": {"search": p_at(list(range(len(short))), fit), "judge": p_at(j_order, fit), "rerank": p_at(r_order, fit)},
               "p10_fit_kind": {"search": p_at(list(range(len(short))), good), "judge": p_at(j_order, good), "rerank": p_at(r_order, good)}}
        report.append(row)
        print(f"\n== {req}: {sum(fit)}/{len(short)} fit by the reference labels, {sum(good)} fit and are the right kind")
        print(f"   judge label accuracy {acc}, fit accuracy {bin_acc}, same labels on all repeats {row['judge_stable_items']}")
        print(f"   latency: judge30 {row['judge30_secs']}s, judge10 {row['judge10_secs']}s, rerank {row['rerank_secs']}s")
        print(f"   rerank AUC fit {row['rerank_auc_fit']}, fit+kind {row['rerank_auc_fit_kind']}; P@10 fit {row['p10_fit']}; fit+kind {row['p10_fit_kind']}")
        for i, v in enumerate(short):
            if pred[i] != gold[i] or (i in r_order[:10]) != (i in j_order[:10]):
                print(f"   {v['asin']} colour={v['colour']!r} size={v['size']!r} kind={kind[i]} gold={gold[i]} judge={pred[i]} "
                      f"rerank={scores[i]:.3f} (rank {r_order.index(i) + 1}) | {v['title'][:60]}")

    print("\nper-constraint reranker (threshold chosen on the other requests):")
    pc_out = {}
    for k, items in per_constraint.items():
        accs, aucs = [], []
        for i, it in enumerate(items):
            train = [(s, y) for j, o in enumerate(items) if j != i for s, y in zip(o["scores"], o["truth"])]
            th = max(sorted({s for s, _ in train}), key=lambda t: sum((s >= t) == y for s, y in train))
            accs.append(sum((s >= th) == y for s, y in zip(it["scores"], it["truth"])) / len(it["scores"]))
            aucs.append(auc(it["scores"], it["truth"]))
            print(f"   {k:6} {it['case']:15} AUC {aucs[-1]}  accuracy {accs[-1]:.2f} at threshold {th:.3f}  {it['secs']:.2f}s")
        pc_out[k] = {"mean_accuracy": round(statistics.mean(accs), 3), "aucs": aucs}
    def mean(f):
        xs = [f(r) for r in report if f(r) is not None]
        return round(statistics.mean(xs), 3) if xs else None
    summary = {
        "judge_fit_accuracy": mean(lambda r: r["judge_fit_accuracy"]),
        "judge30_p50_secs": statistics.median(s for r in report for s in r["judge30_secs"]),
        "judge10_p50_secs": statistics.median(s for r in report for s in r["judge10_secs"]),
        "rerank_p50_secs": statistics.median(s for r in report for s in r["rerank_secs"]),
        "rerank_auc_fit": mean(lambda r: r["rerank_auc_fit"]), "rerank_auc_fit_kind": mean(lambda r: r["rerank_auc_fit_kind"]),
        "p10_fit": {m: mean(lambda r, m=m: r["p10_fit"][m]) for m in ("search", "judge", "rerank")},
        "p10_fit_kind": {m: mean(lambda r, m=m: r["p10_fit_kind"][m]) for m in ("search", "judge", "rerank")},
        "per_constraint": pc_out}
    print("\nsummary:", json.dumps(summary, indent=1))
    print(f"spent ${ev.spent():.3f}")
    json.dump({"summary": summary, "cases": report}, open(OUT, "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
