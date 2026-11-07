# crv requirements

A shopper chats with an assistant that finds and recommends real products from an Amazon Fashion
catalog. One web page shows the chat next to the products found. How the data is cleaned and
searched: [data-and-search.md](data-and-search.md).

## Terms

- **Variant**: one catalog record, with one colour, one size and its own `parent_asin`. There are 39,834.
- **Product**: the variants that belong together, such as one pair of shorts in 3 colours. About 30,400.
- **Hard filter**: an exact condition on a reliable field, such as `price < 120`. Applied before search.
- **Soft constraint**: a preference matched by meaning, such as "blue" or "size small". Found with
  embeddings, then checked by the LLM.
- **Embedding**: a list of numbers that captures what a text or image means. Close embeddings mean
  similar things.
- **Tool**: a function the LLM calls, such as `search_products` or `get_product`.
- **RAG** (retrieval-augmented generation): the LLM answers from catalog rows a tool returned, not from
  memory.
- **Grounded**: every product fact in a reply comes from a field a tool returned in this conversation.
- **Shared photo**: a main photo used by several differently coloured variants of one product. Ingest
  flags it with plain code. 389 variants have one.
- **Photo check**: at runtime, when the shopper asks for a colour, DeepSeek looks at the photos of the
  top 10 products. Products whose photo shows that colour move to the front.
- **Quality score**: how good and complete a listing is, scored across many fields.
- **Popularity**: rating combined with review count.
  Both are defined in [data-and-search.md](data-and-search.md#ranking-quality-and-popularity).
- **Eval set**: a fixed list of test conversations with expected outcomes, run with real LLM calls.
- **Gold set**: the products a person has marked as correct answers for an eval query. Fixed before
  implementation.
- **Judge**: an LLM that grades replies against a written rubric. It is a different model from the
  assistant's.

## Core asks from the brief

| # | The brief asks for | Covered by |
|---|---|---|
| C1 | Answer questions about the catalog and recommend products from it | R1, R2, R3, R9, R11, R12 |
| C2 | A simple interface to chat and see the products found | R8, R10 |
| C3 | Follow-ups that refine results ("anything cheaper?", "only blue ones") | R4 |
| C4 | Real catalog products only. Never invent prices, sizes, availability or specs | R1, R2 |
| C5 | Explain missing information clearly | R5, R7 |
| C6 | Ask questions when they help | R6 |
| — | Stay within limits and fail safely | R13 |

## Scope

In scope: chat, search and recommend, follow-up refinement, clarifying questions, questions about a
product or the catalog, similar products, and a web UI with product cards.

Out of scope for now: stock and availability, cart and checkout, accounts, review text, real-time
prices.

## How criteria are checked

Each criterion names its check:
- **[Code]** is a script comparing the reply, the cards and the turn log against tool results and the
  catalog. It is exact.
- **[Judge]** is the judge LLM with a rubric. Its scores count only after it agrees with at least 50
  human labels at least 90% of the time.
- **[Human]** is a person reviewing a stated sample.

"100%" and "0 violations" mean every case in the eval set, with no exceptions.

## Requirements and acceptance criteria

### R1. Only real products, only real facts (C1, C4)

- **AC1.1** Every product shown or named exists in the catalog. [Code] 100%.
- **AC1.2** Only products a tool returned in this conversation are named. [Code] 100%.
- **AC1.3** Every number equals the catalog: prices to the cent, ratings, review counts. [Code] 0 violations.
- **AC1.4** Every stated colour, size, brand, material or spec matches a returned field. [Judge] 0 violations.
- **AC1.5** Never claims stock, availability or delivery. It says "the catalog lists…" instead. [Judge] 0 violations.
- **AC1.6** Colour means the variant's colour text. When a colour is asked for, products whose photo
  shows it rank first. [Code] 100%.
- **AC1.7** Never mentions a photo/text mismatch. [Judge] 0 violations.
- **AC1.8** At least 9 of the top 10 are the requested kind and pass every hard filter. [Judge] and [Code].
- **AC1.9** For narrow gold queries, at least 80% of the gold products appear in the top 10. [Code].
- **AC1.10** "Running shoes under $120" returns 10 running shoes, all under $120 (206 exist). [Code] and [Judge].

### R2. Hard filters are exact (C1, C4)

- **AC2.1** "Under $X" is `< X`, "up to $X" is `≤ X`, and "between" includes both ends. [Code] 100%.
- **AC2.2** "4 stars and up" is `rating ≥ 4.0`. [Code] 100%.
- **AC2.3** A named brand matches every result, ignoring case. [Code] 100%.
- **AC2.4** List-price filters use trusted values only. The reply says how many products lacked one. [Code] 100%.
- **AC2.5** Cards show `price` as `$X.XX`. List price appears only when asked, called "list price". [Code] 100%.

### R3. Soft constraints are judged, not guessed (C1)

- **AC3.1** Each result is labelled match, partial, no or unknown per soft constraint, with the catalog
  value. [Code] 100%.
- **AC3.2** A "no" is never shown as a match. [Code] 100%.
- **AC3.3** Labels agree with at least 50 human labels at least 90% of the time. [Human].
- **AC3.4** "Orange running shorts in size small": every match is shorts, orange or partly orange, and
  size small. [Judge] 100%.
- **AC3.5** The judge covers at most 30 products. The photo check covers at most 10 photos, and runs
  only when a colour is asked for. [Code] 100%.

### R4. Follow-ups refine the last results (C3)

- **AC4.1** "Anything cheaper?" changes only the price constraint. [Code] 100%.
- **AC4.2** After it, every result costs less than the cheapest shown, or less than the product pointed
  at. If none exist, the reply says so. [Code] 100%.
- **AC4.3** "Only show blue ones" adds only colour. Every result is blue, with photo-blue ones first.
  [Code] and [Judge] 100%.
- **AC4.4** "The second one" and "the Nike one" resolve to the right product. [Code] 100%.
- **AC4.5** Three chained refinements keep all earlier constraints, checked on at least 8 chains. [Code] 100%.
- **AC4.6** On a topic change, the reply says which constraints it kept. [Judge] 100%.
- **AC4.7** Active constraints show as removable pills. Removing one reruns the search. [Code], end to end.

### R5. Missing information is said out loud (C5)

- **AC5.1** A constraint on an empty field is labelled "unknown", and the reply gives the count. [Code] 100%.
- **AC5.2** Asked about an empty field, it says "not listed" and doesn't guess. [Judge] 0 violations.
- **AC5.3** No "nan", "NaN", "None", "null", "undefined" or empty slot appears anywhere. Empty fields
  show "Not listed". [Code] 100%.
- **AC5.4** Asked about what the catalog never has (stock, delivery, returns), it says so. [Judge] 0 violations.

### R6. Clarifying questions when they help (C6)

- **AC6.1** A broad request ("I need shoes") gets one short question with 2–4 quick replies. It may
  show results too. [Judge].
- **AC6.2** At most one question per turn, never about something already said. [Judge] 100%.
- **AC6.3** It asks on at least 8 of 10 broad and at most 2 of 10 specific labelled requests. [Code].
- **AC6.4** Every quick reply leads to at least one product. [Code] 100%.

### R7. No results, or not in the catalog (C1, C5)

- **AC7.1** With zero matches, it names the constraint that caused it and offers to relax it. It never
  drops a constraint silently. [Code] and [Judge] 100%.
- **AC7.2** It relaxes the lowest priority first, and never relaxes price without asking. [Code] 100%.
- **AC7.3** For something not carried ("a laptop"), it says so. Related items (laptop bags) are labelled
  as different. [Judge] 100%.
- **AC7.4** Off-topic: it says what it can help with and shows no products. [Judge] 100%.

### R8. One card per product (C2)

- **AC8.1** No two cards are variants of one product. [Code] 100%.
- **AC8.2** A card shows the product photo, the best-matching variant, and "Seen in" colours and sizes
  from its variants. [Code] 100%.
- **AC8.3** Different variant prices show as a range. [Code] 100%.

### R9. Questions about a product or the catalog (C1)

- **AC9.1** Product questions are answered from catalog fields, naming the field, or with "not listed".
  [Judge] 0 violations.
- **AC9.2** Catalog questions come from a database query. "How many Nike products?" is 777 products
  (994 listings). [Code] exact.
- **AC9.3** Comparisons ("which is cheapest?") match the tool values. [Code] 100%.

### R10. Web UI (C2)

Checked by end-to-end browser tests [Code] and one [Human] pass per release.
- **AC10.1** One page with a chat pane and a results grid. On mobile, a toggle switches between them.
- **AC10.2** Cards lead with the image, then title, price, rating with review count, colour, size and
  brand. Empty fields show "Not listed".
- **AC10.3** The grid updates in the same turn as the reply.
- **AC10.4** Clarifying questions show quick-reply chips that send the answer.
- **AC10.5** Works by keyboard alone. Text meets WCAG 2.2 AA contrast.
- **AC10.6** A one-line note says prices are sample data.

### R11. Ranking: relevance, then quality and popularity (C1)

- **AC11.1** Relevance comes first. Among equal matches, higher quality and popularity rank first: the
  top 10's average beats the average of all matches. [Code].
- **AC11.2** A rating always shows with its review count. Fewer than 5 reviews is never "top rated".
  [Code] and [Judge] 0 violations.
- **AC11.3** Weights are tuned on the eval set and recorded. No per-query rules. [Human].

### R12. Similar products (C1)

- **AC12.1** "More like this" returns the same kind, without the product or its variants. At least 8 of
  10 are judged similar. [Code] and [Judge].
- **AC12.2** No two results share a main image. [Code] 100%.

### R13. Limits and failures

- **AC13.1** At most 5 tool calls per turn, with a median of 2 or fewer. [Code].
- **AC13.2** At the limit, it answers with what it has and says what's unfinished. [Judge] 100%.
- **AC13.3** If Fireworks fails or times out, the shopper sees a plain error with a retry button.
  Nothing appears that a tool didn't return. [Code] fault injection, 100%.
- **AC13.4** Every turn logs its tool calls, arguments, product ids and timings. [Code] 100%.
- **AC13.5** Instructions inside catalog text are ignored. [Judge] 100%.
- **AC13.6** Ingest never calls an LLM, checked by an architecture test. [Code] 100%.

## Non-functional requirements

- **N1. New data needs no rule changes.** Adding records runs only the ingest, which is plain code:
  format cleaning, grouping, flags and scores, plus search indexing for the new rows. There are no LLM
  calls and no vocabulary lists to edit. Test: hold back a random
  10% of the catalog, ingest it afterwards with no code changes, then confirm it is searchable and eval
  scores stay within 5 points.
- **N2. Proven with real services.** The eval set runs against the real LLM and embedding APIs, and
  results are kept per run. Mocks are only for unit tests.
- **N3. Speed.** Measured from send to grid update, and reported as p50 and p90 per run. First words of
  a reply within 3 s. Results grid within 6 s at p90. These targets are to be confirmed after the
  first Fireworks tests.
- **N4. Tests.** Unit, integration and end-to-end tests cover at least 80% of changed lines.

## Eval set

About 60 items, written and frozen before implementation:

| Items | What they test |
|---|---|
| 12 searches, including both brief examples, each with a gold set | R1, R2, R3, R11 |
| 8 refinement chains of 3 or more turns | R4 |
| 20 single requests, 10 broad and 10 specific | R6 |
| 6 questions about empty fields | R5 |
| 4 out-of-catalog or off-topic requests | R7 |
| 4 questions about the whole catalog | R9 |
| 4 similar-product requests | R12 |
| 2 fault-injection runs and 1 injected-text product | R13 |

Rules:
- Gold sets are built by labelling every candidate from a broad scan of the catalog. A person
  reviews them before they are frozen. Changing one needs a written reason.
- The judge is a different model from the assistant. It has one rubric per criterion.
- Each run stores replies, cards, turn logs, scores and timings.
- A run passes when every "100%" and "0 violations" check passes and every threshold is met.
- No criterion may go from pass to fail between runs.

## Decisions so far (2026-11-07)

1. Show `price` as the catalog price. It is synthetic for every record, which is fine. A one-line note
   in the UI says so.
2. Keep `original_price` as a searchable "list price". It is the real Amazon price and covers 32%.
   Implausible values are found statistically, never with a fixed cut-off. Only values far below
   their category count as suspect, since high outliers are mostly real. No LLM review. The assistant filters on it or mentions it only when the shopper asks. Cards show
   `price`.
3. Ignore availability for now.
4. No colour or size vocabulary at ingest. Match them at search time with embeddings plus the LLM
   judge. For colour, the text tells variants apart and the photo decides the order (decision 13).
5. Group variants into products.
6. Filter on reliable fields first, then run hybrid search inside the filtered set. Fields are ranked
   by priority: price first, images last.
7. Use one embedding model for both text and images.
8. Repo: `github.com/Yazington/crv` (private).
9. Tech stack:
   - Frontend: React.
   - Backend: Java with Spring Boot, in controller, service and repository layers, with entities and
     DTOs. Layer rules and lint rules for both sides: [code-standards.md](code-standards.md).
   - Agents use SDKs end to end, from the backend to the frontend.
   - API schemas are OpenAPI. The backend publishes them and the frontend consumes them.
   - Models: the Fireworks API serves both the LLM and the embeddings.
10. The agent may make several tool calls per turn, up to 5.
11. Ranking is relevance first, then quality (many fields) and popularity (rating and review count).
12. The Fireworks key is in the personal Vaultwarden as "crv / Fireworks".
13. Text for the variant, photo for ranking at runtime. Shoppers look at pictures, but a photo often
    shows another variant of the same product (7 of 14 checked). So:
    - the colour text tells variants apart;
    - ingest flags shared photos with plain code;
    - when the shopper asks for a colour, DeepSeek checks the top 10 photos and moves the ones that
      show it to the front;
    - the assistant never mentions a disagreement between photo and text.
14. The LLM is DeepSeek V4.1 Flash on Fireworks (`accounts/fireworks/models/deepseek-v4p1-flash`). It
    costs $0.30 per million input tokens and $1.20 per million output tokens. **DeepSeek runs only at
    runtime**, while a shopper chats. Data cleaning is plain code with no LLM.
15. The catalog is stored in Postgres with pgvector and built with Gradle. Cleaning and ingest are Java
    in the backend. The scripts in `scripts/` are exploration only.

## Open questions

- Which Fireworks embedding model? Only `voyage-multimodal-3-5` takes images, and it runs only on a
  dedicated GPU, not pay-per-call. The pay-per-call `qwen3-embedding-8b` is text only, which would
  drop image embeddings and conflict with decision 7. Details: [data-and-search.md](data-and-search.md#embeddings).
- Which agent SDK on the backend, and which chat SDK on the frontend?
- Is the OpenAPI spec generated from the Spring code or written first, and how does the frontend turn
  it into types?
- Design system: shadcn/ui on Base UI with Tailwind v4 is proposed, still under discussion in the
  design thread.
