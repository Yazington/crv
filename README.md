# crv

crv is a chat shopping assistant over a real Amazon Fashion catalog: 39,834 listings grouped into 30,386
products (clothing, shoes, jewelry, luggage, outdoor). Shoppers chat ("running shoes under $120",
"anything cheaper?", "only blue ones") and see the real products it finds. It never invents prices,
sizes or specs, and it says plainly when the catalog doesn't list something.

## Run it

Run the backend on the desktop, from `backend/`. The JDK comes from the Nix shell, and Postgres starts
from `compose.yaml`.

```bash
nix develop .. -c ./gradlew build                     # lint, compile, all tests
nix develop .. -c ./gradlew bootRun --args='--crv.ingest.file=<products.json> --spring.main.web-application-type=none'
FIREWORKS_API_KEY=... nix develop .. -c ./gradlew bootRun --args='--server.port=8191'
```

The UI, from `frontend/`:

```bash
npm install && npm run dev    # http://127.0.0.1:5181; set CRV_API to point /api at another backend
```

The OpenAPI spec is at `/v3/api-docs`. The Fireworks key is in the personal Vaultwarden as
"crv / Fireworks".

## Where things are

| Path | What |
|---|---|
| `backend/` | Spring Boot 4.1 on Java 25, Postgres with pgvector, Flyway migrations: `V1` catalog, `V2` full-text search, `V3` clicks |
| `frontend/` | React 19 with Vite: chat, product cards, homepage, product page |
| `docs/requirements.md` | What to build, with one-line acceptance criteria (R1–R13), and the decisions so far |
| `docs/data-and-search.md` | Catalog findings, cleaning, field priority, search flow, measured runtime settings |
| `docs/code-standards.md` | Layers, lint rules, commands |
| `scripts/` | Python exploration and eval scripts. Not part of the app |

## Backend layers

Calls go one way: `controller` → `service` → `repository`.
- `entity` holds the stored JPA classes and never leaves the service layer.
- `dto` holds the Java records that make up the API and the OpenAPI schemas.
- `cli` runs the ingest.

ArchUnit tests enforce the layers. Error Prone, NullAway, Spotless and `-Werror` fail the build.
`LlmBoundaryTest` checks that ingest never depends on an LLM.

## The catalog

Ingest is plain code, with no LLM:
- it turns `"nan"` into empty values, unescapes HTML and fixes dates;
- it groups variants (one colour and size each) into products, using three signals;
- it scores each listing's quality (9 completeness checks) and popularity (rating times a dampened
  review count);
- it marks list prices far below their category as suspect;
- it flags photos shared by differently coloured variants.

## The agent

DeepSeek V4.1 Flash on Fireworks, through Spring AI 2.0, used only at runtime.
- `AssistantService` runs the tool loop itself. It allows at most 5 tool calls per turn, logs every
  call in the reply's `trace`, and returns a plain error, with no products, when Fireworks fails.
- Tools (`ShopperTools`): `search_products`, `get_product`, `catalog_stats`, `similar_products`
  and `suggest_replies` (quick-reply chips).
- Each conversation is kept in memory with its active search, which the UI shows as pills, and the
  cards shown last. That's how "anything cheaper?" and "the second one" resolve.
- Search (`SearchService`):
  1. exact hard filters (price, rating, brand, audience);
  2. Postgres full-text on title and category path;
  3. ranking by relevance, then quality and popularity;
  4. a **fit check**: DeepSeek with thinking off labels kind, colour and size as match, partial,
     unknown or no, in batches of 30, up to 3 batches, and drops "no";
  5. when a colour is asked for, a **photo check** on the top 10 moves products whose photo shows
     the colour first.

A turn takes about 2–5 s.

## Recommendations

- **Homepage** (`/api/home`):
  - The browser keeps an anonymous shopper id. Card clicks from the homepage, chat, search or
    similar products are stored (`/api/interactions`).
  - A new shopper sees random products, with complete listings more likely.
  - After that, the homepage shows products like the last 5 clicked, never repeating them.
- **More like this:** the same deepest category and shared title words, without the product itself
  or repeated photos.

## UI

- One page with a chat pane and numbered product cards: photo, title, price or price range, rating
  with review count, colour, size, brand. Empty fields read "Not listed".
- The active search shows as removable pills; removing one reruns the search.
- Clarifying questions come with quick-reply chips. Failures show a retry button.
- The homepage feed is titled "Explore" for new shoppers and "Picked for you" after clicks.
- The product page has a gallery, a colour and size picker, details and "More like this".

## What's missing

- **Image search.** There are no image embeddings. Photos only reorder colour searches. Open choice:
  an open-weights model on the desktop GPU (recommended), Voyage's API, or a Fireworks dedicated
  deployment.
- **Embeddings and reranker:** neither is used. Search is full-text plus the fit check. A reranker
  pre-sort could save about 1.3 s per search.
- **Streaming replies.**
- **Verification:** the last eval ran on a Python stand-in for the tools. It needs re-running against
  `/api/chat`. The eval set's hand-labelled correct answers aren't frozen yet.
- **Frontend:** no lint, formatting or tests. API types are written by hand, not generated from
  OpenAPI. Variant switching and clicks on chat cards aren't verified. Some detail rows repeat.
- **Running in production:**
  - conversations live in memory, so they're lost on restart and limited to one instance;
  - there's no CI and no deployment;
  - prices are synthetic sample data.
- **Data:** about 95 records still carry junk category nodes ("Swimwear TEST").
- **Docs:** the homepage, the product page and the fit check's batches aren't in `docs/` yet.
