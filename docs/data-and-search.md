# Catalog data and search design

What is in the catalog, how we clean it, and how the assistant searches it. Requirements and
acceptance criteria: [requirements.md](requirements.md). All numbers were measured on
`products.json` (39,834 records) with the scripts in `scripts/`.

## Source

- The original metadata is McAuley-Lab/Amazon-Reviews-2023. Some fields were flattened and added on
  top: `color`, `size`, `brand`, `price_source` and others.
- The file is one JSON array with one record per line, so it can be read line by line with little memory.
- **Every `price` is synthetic** (`price_source: "synthetic"`). It is plausible per category: road
  running shoes have a median of $107. It is random across variants of one product: the same shorts
  cost $35–$96 by size.
- The synthetic price ignores luxury. A 2–20 carat diamond tennis bracelet with an $8,369 list price
  has a catalog price of $203.89.
- `original_price` looks like real Amazon list prices ($19.99, $100.00). It covers only 32% and has
  some junk, such as $0.01. High values are often real; the $8,369 bracelet is one. See "List price
  checks" below.

## Data types

| Type | Fields | Use |
|---|---|---|
| Text | title, features, description, details values, category path | Keyword search, text embedding, answers |
| Numbers | price, original_price, average_rating, rating_number, date_first_available, best-seller ranks | Hard filters, sorting, ranking |
| Labels and IDs | store, department, main_category, parent_asin | Filters, grouping |
| Images | 177,884 image URLs; a MAIN image on 39,805 records; a hi-res version on 93% of images | Image embedding, cards |
| Video | 16,569 links on 6,053 records | Not usable: they point to Amazon video pages, not video files |

There is no audio and there are no PDFs.

## Field priority

Priority is how much a field matters to a shopper's request. Method is how reliable the field is.
Reliable fields become hard filters. Messy ones are matched by meaning and then checked.

| # | Field | Coverage and quality | Method |
|---|---|---|---|
| 1 | `price` | 100%, numeric, $5–$855 | Hard filter and sort |
| 2 | Product type, from the category path | 100%, but there are two overlapping trees ("Women > Shoes > … > Road Running" and "Sport Specific Clothing > Running > …"), plus about 50 junk nodes | Strong ranking signal, not a filter. Becomes a filter only if the eval set shows it is safe |
| 3 | Audience (women, men, boys, girls, baby), from category level 2 plus `department` | Known for 72%, unknown for 28% | Filter only when the shopper states it. Unknown passes through and is labelled |
| 4 | Colour: the `color` text per variant, plus the photo for ranking | The text is free text: 13,129 values, 24% without a basic colour word ("203n"). In 58 random checks, the photo disagreed with the text in 4, often because it shows a sibling variant | Soft. The text tells variants apart. The runtime photo check reorders the top 10 (see "Photos and colour") |
| 5 | `size` | 100% present, free text, one size per variant ("X-large/US L", "medim") | Soft: LLM judge. Product grouping supplies the other sizes |
| 6 | Brand: `store`, falling back to `brand` | `store` 99.9%; `brand` is "nan" 54% of the time | Hard filter when named, ignoring case |
| 7 | `average_rating` and `rating_number` | 100%, numeric. 30% have fewer than 5 reviews | Filter if asked. Otherwise a ranking signal weighted by review count |
| 8 | Title | 100% (6 empty) | Keyword search and text embedding |
| 9 | Features and description | 97% / 100% | Text embedding and answers |
| 10 | Details: material 72%, style 56%, sport 31%, closure 12% | Sparse | Text embedding and answers. Empty means "Not listed" |
| 11 | Images | MAIN image on 99.9%. 389 variants share a photo with a differently coloured sibling | Lead every card. Checked at runtime for colour requests |
| 12 | `original_price`, shown as "list price" | 32%. 1.4% are flagged as implausible | Hard filter and mention, only when the shopper asks. Suspect values are never used |
| 13 | Best-seller rank | 45%, stored as a Python-dict string | Optional popularity tiebreak |
| — | Dimensions, weight, country, videos, `main_category`, `bought_together` | 0–42%, or unreliable | Answers only, or ignored |

## Cleaning at ingest: plain code only

No LLM runs at ingest. DeepSeek is used only at runtime. These steps never depend on a vocabulary, so
new data passes through them unchanged.

- `"nan"`, `"None"` and `""` become null in every field.
- Parse numbers and dates. Dates after today become null (2 records).
- Unescape HTML entities (157 records) and trim whitespace.
- Lowercase `department`.
- Drop junk category nodes ("Swimwear TEST", "Green Lit 198"), but keep the products.
- Skip records with an empty title (6).
- Brand becomes `store`, falling back to `brand`.
- Keep raw `color` and `size` strings as they are. No mapping tables.
- Attach every photo to its product, and flag shared photos ("Photos and colour" below).
- Screen list prices statistically ("List price checks" below).

## Photos and colour

Text for the variant, photo for ranking at runtime.

Why:
- Shoppers look at pictures, not text, so the photo matters.
- But a photo often shows another variant of the same product. Of 14 photos a vision model flagged as
  not matching the colour text:
  - 7 were the same photo file as a sibling variant's;
  - 2 showed a colour no sibling in our data has, probably a variant missing from the catalog;
  - 5 had no siblings, for example a referee shirt whose photo is a mini camera.
- The colour text is what tells variants apart.

How it works:
- **At ingest (plain code):** every photo belongs to its product. A photo used by several differently
  coloured variants is flagged as shared (389 variants).
- **The card** shows the product's photo, and "Seen in" lists the variant colours from the text.
- **Colour search:**
  - The variant colour text is matched first.
  - Then, only when the shopper asked for a colour, DeepSeek looks at the photos of the top 10
    products, in parallel. Products whose photo shows that colour move to the front.
  - Measured: 10 photos in parallel take 0.8–1.3 s and cost about $0.001 in total (see "Runtime
    settings").
- The assistant never mentions a disagreement between photo and text.

Evidence (`scripts/photo_colour_check.py`, `scripts/colour_photo_conflicts.py`, 2026-11-07):
- Of 58 random photos, 48 matched the colour text, 6 matched partly and 4 disagreed. I checked all 4 by
  eye.
- DeepSeek and Kimi K3 read the same colours from the photos. They differed only on whether to call a
  difference "partial" or "mismatch".
- DeepSeek took 1.3 s per photo (median), Kimi 2.7 s.
- 2 of 80 Kimi calls failed with a server error, so calls need retries.

## List price checks

A fixed cut-off would be wrong in both directions. $8,369 is a real diamond bracelet, and $47 is
normal for a hoodie. So the check is statistical, in plain code.

**Screen** (`scripts/list_price_outliers.py`):
- Compare each list price with the others in its category leaf, using log prices. If a leaf has fewer
  than 20 prices, use its parent category instead.
- Flag a price that sits far from the category's typical value: a modified z-score above 3.5, which
  is the usual cut.
- The spread is floored at about ×1.35, so a category where most prices are identical doesn't flag
  ordinary prices.
- This flags 184 of 12,931 (1.4%): 59 low and 125 high.

**Only low flags count as suspect.** A suspect value stays stored, but it is never shown or filtered
on.
- Below the typical range, the screen is reliable. All 5 checked were junk, such as $0.01 bras and
  $0.18 keychains.
- Above it, the screen mostly catches real luxury items: 14k solid gold bracelets at $3,347, a TYR
  racing suit at $583. Rare junk such as Burton glove liners at $1,763 stays in. That is accepted,
  because list prices are shown only when the shopper asks.

## Grouping variants into products

A record is one variant. Two records join the same product when any of these signals match:
1. the same title and store;
2. the same title and store after removing the variant's own colour and size words;
3. the same store, the same rating and review count, and the same first four title words. Amazon
   shares one review count across all variants of a product.

Result: about 30,400 products. 26,510 have one variant, 3,467 have 2–5, 415 have 6 or more, and the
largest has 120. How often it merges two different products hasn't been measured yet.

## Search flow

The LLM calls tools. RAG is what the search tool returns.

1. **Parse.** The LLM turns the message into a `search_products` call. The call carries hard filters
   (price, rating, brand, audience), soft constraints (colour, size, material and so on) and a query
   text. The server keeps the active constraints, so follow-ups edit them instead of starting over.
2. **Filter.** An exact database query on the hard filters.
3. **Hybrid rank inside the filtered set.** Keyword search, a text-embedding match and an
   image-embedding match are merged with weights that follow the priority table. Text weighs more
   than images. The weights are tuned on the eval set, not set by hand.
4. **Group.** Variants are folded into products. The best-matching variant leads.
5. **Judge (runtime).** DeepSeek checks the top ~30 products against each soft constraint, size included, and labels them match,
   partial, no match or unknown, with the raw value.
6. **Relax.** If too few match, the lowest-priority constraint is relaxed first and the reply says so.
   Price is never relaxed without asking.

The agent may make up to 5 tool calls per turn. For example, it can search, open a product, then
search again.

Tools:
- `search_products`, the steps above;
- `get_product`, all fields for one product, used for questions about it;
- `catalog_stats`, counts, price ranges and brands for a set of filters, used for questions about the
  whole catalog ("how many Nike products?");
- `similar_products`, products like a given one (see "Similar products" below).

## Runtime settings (measured 2026-11-07)

DeepSeek V4.1 Flash on Fireworks, with real catalog rows. Scripts: `scripts/test_tool_calling.py` and
`scripts/test_judge.py`.

| Step | Setting | Result |
|---|---|---|
| Agent turn (choosing tools and arguments) | `reasoning_effort: low` | 8 of 8 argument checks right, including "anything cheaper?" and "only blue ones". 0.8 s median, 1.3 s p90 per call |
| Judge 30 products on colour and size | `reasoning_effort: none` | 0 errors on 2 samples of 30, about 2 s |
| Photo check, 10 photos in parallel | `reasoning_effort: none`. Fireworks fetches the image URL itself | 0.8–1.3 s total, about 240 input tokens per photo |

What these runs showed:
- **Thinking is the latency.** With `low`, the judge spent 2,900 thinking tokens and took 13 s for 30
  products. With `none` it takes 2 s.
- **With thinking off, the prompt must be exact.** An example in the prompt ("Medium/US S counts as
  small") made it label every "Medium" as small. The fixed rule: a size matches only if the label
  itself names that size. "S", "Small" and "US S" match; "Medium", "Large" and "X-Small" don't; "One
  Size" is unknown.
- **Expected turn time** is about 0.8 s (agent) + search + 2 s (judge and photo check, run in parallel)
  + about 1 s (reply). That is within N3's 6 s p90 target.

## Ranking: quality and popularity

Relevance comes first. Among products that match equally well, quality and popularity decide the
order. The weights are tuned on the eval set (`scripts/quality_score.py`).

**Quality** is the share of these 9 checks a listing passes:
- a clear title (10–150 characters);
- 3 or more feature lines;
- a real description (over 200 characters);
- brand known;
- material listed;
- 3 or more images;
- a hi-res image;
- a rating from 5 or more reviews;
- a rating of 4.0 or higher.

How the catalog scores:
- 21% pass all 9 and 32% pass 8.
- About 22% pass 6 or fewer.
- Complete listings make better recommendations, because the assistant can answer questions about
  them instead of saying "not listed".

**Popularity** is rating times a dampened review count:
`popularity = (average_rating / 5) × log(1 + rating_number) / log(1 + 40,455)`.
- 40,455 is the largest review count in the catalog, so the result runs from 0 to 1.
- The log keeps a product with 40,000 reviews from swamping one with 400.

## Similar products

"More like this" finds the products nearest to a given one by text and image embeddings, within the
same kind of product. It leaves out that product's own variants. 927 main images are reused under
different titles, so results with the same main image are dropped.

How it scales:
- **More products.** Checking every product is fine up to about 100K. Beyond that, an approximate
  index (HNSW, a graph searched by hopping between close neighbours) keeps a search at milliseconds
  over millions of products. Lucene and Postgres's pgvector both have one.
- **Memory.** A million products at 1,024 numbers each take about 4 GB, or about 1 GB stored
  compactly.
- **More users.** Searches only read, so more users means more servers. "More like this" is the same
  for every shopper, so it can be computed once per product and cached.
- **With many users,** click and purchase data ("people who viewed this also viewed") usually beats
  similarity between products. That is the time to add it. Similarity stays useful for new products
  nobody has clicked yet.

## Embeddings

**Decision (2026-11-07): Fireworks serves the embeddings**, as well as the LLM. Its embeddings API is
OpenAI-compatible, at `https://api.fireworks.ai/inference/v1/embeddings`. The two options, checked
2026-11-07:
- `fireworks/voyage-multimodal-3-5` puts text and images in one space. It runs only on a dedicated GPU
  deployment, not serverless (shared, pay per call), and the model page shows no price.
- `fireworks/qwen3-embedding-8b` is serverless but text only.

Which one is still open. The Gemini and Voyage comparison below predates this decision and is kept
for reference.

Sources: [Fireworks embeddings guide](https://docs.fireworks.ai/guides/querying-embeddings-models),
[voyage-multimodal-3-5 on Fireworks](https://fireworks.ai/models/fireworks/voyage-multimodal-3-5)

Earlier recommendation: **Gemini Embedding 2** (`gemini-embedding-2`, stable, April 2026).
- It puts text, images, video, audio and PDFs in one space.
- It offers 128–3,072 dimensions and up to 6 images per request.
- It can return separate embeddings or one combined embedding.
- Price: $0.20 per million text tokens and $0.00012 per image. The Batch API costs half.
- The whole catalog is about 17M text tokens plus 39,805 main images. That's about $8, or $4 through
  the Batch API.
- We store two vectors per variant, one for text and one for the image, so their weights can differ.
  Elastic's product-search test found that combining separate image and text embeddings beat either
  one alone.

Alternatives:
- **Voyage Multimodal 3.5:** takes 1,000 inputs per request and 32K tokens per input. Its free tier
  (200M text tokens and 150B pixels) likely covers the whole catalog; image sizes not checked.
- **Qwen3-VL-Embedding** (open weights) on the desktop GPU would have no API cost. We cut it because
  search would then need a model server running all the time.

Sources:
- [Gemini Embedding 2 model](https://aistudio.google.com/docs/models/gemini-embedding-2)
- [Gemini embeddings guide](https://aistudio.google.com/docs/embeddings)
- [Gemini pricing](https://aistudio.google.com/docs/pricing)
- [Voyage multimodal docs](https://docs.voyageai.com/docs/multimodal-embeddings)
- [Voyage pricing](https://docs.voyageai.com/docs/pricing)
- [Elastic: multimodal embeddings for e-commerce](https://www.elastic.co/search-labs/blog/multimodal-embeddings-ecommerce-product-search)
- [Qwen3-VL-Embedding](https://github.com/QwenLM/Qwen3-VL-Embedding)
