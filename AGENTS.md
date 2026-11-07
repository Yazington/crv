# crv

A chat shopping assistant over an Amazon Fashion catalog, with a web UI.

- What to build, and the acceptance criteria: [docs/requirements.md](docs/requirements.md)
- Tech stack: React frontend, Java and Spring Boot backend, agent SDKs end to end, OpenAPI schemas,
  Fireworks for the LLM and embeddings. Decisions and open choices: [docs/requirements.md](docs/requirements.md#decisions-so-far-2026-11-07)
- Code layout, backend layers and lint rules: [docs/code-standards.md](docs/code-standards.md)
- Catalog findings, cleaning, field priority, search flow, embeddings: [docs/data-and-search.md](docs/data-and-search.md)
- The raw catalog lives in `data/raw/products.json` (gitignored; unzip `products.zip`). Read it line by line.
- Exploration scripts are in `scripts/`. Run them with `python3 -I scripts/<name>.py data/raw/products.json`.
