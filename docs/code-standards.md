# Code standards

How the backend and frontend code is laid out, and the lint rules that enforce it. Stack decisions:
[requirements.md](requirements.md#decisions-so-far-2026-11-07). Tools and versions were checked on
2026-11-07. Every check below fails the build, locally and in CI.

## Backend: Java and Spring Boot

Spring Boot 4.1 (Spring Framework 7) on Java 25, the current long-term-support release, built with
Gradle (Kotlin DSL). Postgres 18 with pgvector stores the catalog. The code is in `backend/`; the JDK
comes from the repo's Nix dev shell (`nix develop`), and Postgres runs from `backend/compose.yaml`.

### Layers

| Package | Holds | Rules |
|---|---|---|
| `controller` | `@RestController` classes | HTTP only: validates input, calls a service, returns DTOs. No business logic |
| `cli` | Commands such as the ingest | Like a controller, but started from the command line |
| `service` | Business logic | Owns transactions. Maps entities to DTOs and back |
| `repository` | Data access | Reads and writes entities. Called only by services |
| `entity` | What is stored | Never leaves the service layer |
| `dto` | What crosses the API, as Java records | Describes the API, so the OpenAPI schemas come from it |

Calls go one way: controller or cli → service → repository.

### Checks

- **ArchUnit** (layer rules written as tests):
  - each layer is called only by the one above it, and nothing calls a controller;
  - controllers and DTOs never reference entities, so an entity can't leak into the API;
  - `@Transactional` appears only in services;
  - `@RestController`, `@Service` and repository classes end in `Controller`, `Service` or `Repository`
    and live in that package;
  - constructor injection only (no `@Autowired` fields), no `System.out`, no `java.util.logging`.
- **Error Prone** (compile-time bug checks), with warnings raised to errors.
- **NullAway** (null checks at compile time). Every package is marked `@NullMarked` (JSpecify), so a
  value that can be null must be typed `@Nullable` and checked before use. The options are the ones
  Spring Framework 7 recommends: `OnlyNullMarked=true` and
  `CustomContractAnnotations=org.springframework.lang.Contract`. Full JSpecify mode (generics,
  arrays) comes later, as Spring advises.
- **Spotless** with palantir-java-format, a single code format.
- `javac -Xlint:all -Werror`.

### Commands

Run these on the desktop, from `backend/`. The JDK comes from the Nix dev shell.

- Build, lint and test: `nix develop .. -c ./gradlew build`. Tests start their own Postgres in Docker
  (Testcontainers). Coverage report: `build/reports/jacoco/test/html/index.html`.
- Format: `nix develop .. -c ./gradlew spotlessApply`.
- Ingest the catalog, then exit: `nix develop .. -c ./gradlew bootRun --args='--crv.ingest.file=<path to
  products.json> --spring.main.web-application-type=none'`. This replaces the stored catalog.
- Serve the API on `127.0.0.1:8190`: `nix develop .. -c ./gradlew bootRun`. Both commands start Postgres
  from `compose.yaml`. The OpenAPI spec is at `/v3/api-docs`.

## Frontend: React and TypeScript

React 19 on TypeScript 7, the native compiler.

### Typing

- `tsconfig`: `strict` (already the default since TypeScript 6, but set explicitly),
  `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`,
  `noPropertyAccessFromIndexSignature`, `noFallthroughCasesInSwitch`, `verbatimModuleSyntax`.
- API types come only from the generated OpenAPI file. There are no hand-written API types, and
  `fetch` is banned (`no-restricted-globals`) outside the API client.

### Lint: Oxlint, type-aware

`oxlint --type-aware --type-check` (with `oxlint-tsgolint`) also reports TypeScript errors, so CI
needs no separate `tsc` step. Rules, all as errors:
- Types: `no-explicit-any`, `no-non-null-assertion`, the `no-unsafe-*` family,
  `strict-boolean-expressions`, `switch-exhaustiveness-check`, `no-unnecessary-condition`,
  `consistent-type-imports`, `use-unknown-in-catch-callback-variable`.
- Promises: `no-floating-promises`, `no-misused-promises`, `await-thenable`, `only-throw-error`.
- React (`react` plugin): `rules-of-hooks`, `exhaustive-deps`, `only-export-components`.
- Accessibility (`jsx-a11y` plugin), which backs AC10.5 (keyboard use, WCAG 2.2 AA).
- `no-console`.

**Prettier** formats.

Why not ESLint: typescript-eslint, which provides its type-aware rules, supports TypeScript only below
6.1, and `eslint-plugin-jsx-a11y` doesn't support ESLint 10. Oxlint's type-aware mode is built on
TypeScript 7, has been stable since July 2026, and covers 59 of the 61 typescript-eslint
type-aware rules.

## Left out

- Backend: Checkstyle and PMD (the formatter and Error Prone cover most of it), SpotBugs (overlaps
  Error Prone), SonarQube, MapStruct (services map by hand; records are small), Spring Modulith (too
  much for one small app).
- Frontend: ESLint, a second linter, oxfmt (not yet 1.0), and import-cycle and dead-code tools.


Sources: [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html),
[NullAway](https://github.com/uber/NullAway),
[Spring null safety](https://docs.spring.io/spring-framework/reference/core/null-safety.html),
[Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Oxlint type-aware linting](https://oxc.rs/docs/guide/usage/linter/type-aware.html),
[tsgolint](https://github.com/oxc-project/tsgolint),
[Oxlint plugins](https://oxc.rs/docs/guide/usage/linter/plugins.html).
