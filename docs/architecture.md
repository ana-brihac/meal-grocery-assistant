# Architecture

## What this is

A grocery-receipt-to-nutrition pipeline: upload a receipt photo, it gets OCR'd and parsed into
inventory items; separately, a user logs what they ate and the app looks up nutrition facts and
tracks spending against their receipts.

## Services

```
                    ┌─────────────────┐
   receipt image →  │   java-backend   │ ──→ Gemini (OCR)
                     │  Spring Boot 3   │
   nutrition log →   │  Java 21, :8080  │ ──→ USDA FoodData Central (nutrition)
                     │                  │
   HTTP requests →   │                  │ ──→ ml-service (Python, :8000) — stub only, see docs/ml-service.md
                     └────────┬─────────┘
                              │ JDBC
                              ▼
                     ┌──────────────────┐
                     │   Postgres 16    │
                     │   (pantrydb)     │
                     └──────────────────┘
```

- **java-backend** — the only service with real functionality. Owns all HTTP endpoints and all
  persistence. See `docs/backend-api.md`.
- **ml-service** — a FastAPI scaffold with a single `/ping` route. Not wired into any real feature
  yet. See `docs/ml-service.md`.
- **Postgres** — schema is hand-written SQL in `db/init/`, applied only on first container start
  (no Flyway/Liquibase). See `docs/database.md`.

## Package layout (`java-backend/src/main/java/com/yourname/mealassistant`)

> Note: `com.yourname` is a leftover template package name, not a real org — nobody has renamed it.

| Package | Owns |
|---|---|
| `receipt` | `Receipt` entity, upload endpoint, async OCR→parse→save pipeline |
| `receipt.parser` | `ReceiptParser` — turns Gemini's JSON text into `InventoryItem` rows |
| `inventory` | `InventoryItem` entity, list/add endpoints, ml-service ping passthrough |
| `nutrition` | Food logging, USDA-backed nutrition lookup + cache, date-range summary |
| `spending` | Date-range spend summary over `receipts` |
| `dashboard` | Combines nutrition + spending summaries into one response |
| `common.client` | External HTTP clients: `OcrClient` (Gemini), `NutritionApiClient` (USDA), `MlServiceClient` |
| `common.dto` | `ApiResponse<T>` — success/data/error envelope (only used by some controllers, see below) |
| `common.exception` | `GlobalExceptionHandler` — catches `NutritionApiException` → 502, everything else → 500 |
| `common.util` | `ItemNameNormalizer` — lowercases + strips quantity tokens (`1L`, `200g`, ...) from food names |
| `config` | `WebClientConfig` (USDA WebClient bean), `AsyncConfig` (currently empty — see `docs/known-issues.md`) |

## Data flows

**Receipt → inventory** (fire-and-forget, no status endpoint):
`POST /api/receipts/upload` → `ReceiptService.processReceiptAsync` runs OCR, parsing, and the
inventory save on `CompletableFuture.supplyAsync`'s default pool (the common `ForkJoinPool`, *not*
a Spring-managed executor — `AsyncConfig` exists but is empty and unused). The HTTP response
returns immediately with "processing in background"; failures are only logged to stderr, never
surfaced to the caller.

**Nutrition log → summary**:
`POST /api/nutrition/log` normalizes the item name (`ItemNameNormalizer`), looks it up in
`nutrition_info` (cache), falls back to a USDA search on a miss, and caches the result — including
a null-macro row if USDA has no match, so a bad lookup never gets retried. `NutritionLog` rows are
saved under the *normalized* name so `GET /api/nutrition/summary` can join them back to
`nutrition_info` correctly. See `docs/third-party-integrations.md` for the USDA integration
details and its rough edges.

**Dashboard**:
`GET /api/dashboard/summary` just calls `NutritionService.getSummary` and
`SpendingService.getSpendingSummary` and wraps both in one response — no independent logic of
its own.

## Conventions worth knowing

- `ApiResponse<T>` (`{success, data, error}`) is used by `ReceiptController` and
  `InventoryController`, but **not** by `NutritionController`, `SpendingController`, or
  `DashboardController`, which return raw DTOs or `ResponseEntity<Void>`. There's no single
  consistent response envelope across the API — check the controller you're calling.
- Services are plain constructor-injected `@Service`/`@Component` beans, no interfaces, no
  builders — straightforward to read and extend.
