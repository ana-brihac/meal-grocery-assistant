# Backend / API

Spring Boot 3.3.4, Java 21. Single module at `java-backend/`. See `docs/architecture.md` for the
package layout and `docs/database.md` for persistence.

## Endpoints

| Endpoint | Method | Body / params | Returns |
|---|---|---|---|
| `/api/receipts/upload` | POST | multipart `file` | `ApiResponse<String>` — immediately, before OCR finishes (see below) |
| `/api/inventory` | GET | — | `ApiResponse<List<InventoryItem>>` |
| `/api/inventory` | POST | `{name, quantity}` (`ItemRequest`) | `201`, `ApiResponse<InventoryItem>` |
| `/api/nutrition/log` | POST | `{userId, itemName, quantityGrams}` (`LogMealRequest`) | `201`, empty body |
| `/api/nutrition/log-recipe` | POST | `{recipeId, servings}` (`LogRecipeRequest`) | `201`, empty body — writes one `nutrition_log` row per recipe ingredient, no `userId` (see `NutritionService.logRecipe`) |
| `/api/nutrition/summary` | GET | `userId`, `from`, `to` (ISO-8601 datetimes, e.g. `2026-08-01T00:00:00`) | `NutritionSummaryResponse` (raw, not wrapped) |
| `/api/nutrition/calendar` | GET | `start`, `end` (ISO-8601 dates, e.g. `2026-08-01`) — no `userId` | `ApiResponse<List<DailyNutritionSummary>>` — one entry per calendar day in range, zero-log days included as zero-totals |
| `/api/spending/summary` | GET | `userId`, `from`, `to` (ISO-8601 dates, e.g. `2026-08-01`) | `SpendingSummaryResponse` (raw, not wrapped) |
| `/api/dashboard/summary` | GET | `userId`, `from`, `to` (ISO-8601 dates) | `{nutrition: NutritionSummaryResponse, spending: SpendingSummaryResponse}` |
| `/api/preferences` | GET | — | `ApiResponse<UserPreference>` — falls back to defaults (2000 cal / 100g protein / 30g fiber / 100 budget) if no row exists yet |
| `/api/preferences` | PUT | `UserPreference` body | `ApiResponse<UserPreference>` — upserts the single row (always `id=1`, see `docs/database.md`) |
| `/api/recipes/search` | GET | `ingredients` (repeated, e.g. `?ingredients=egg&ingredients=milk`) — no `userId` (decided, see below); optional `rankBy=mealHistory` | `ApiResponse<RecipeSearchResponse>` — only recipes whose *entire* ingredient list is covered by `ingredients` (not "any overlap"). Ordered fewest-ingredients-first by default; `rankBy=mealHistory` re-ranks by meal-history embedding similarity via `ml-service` (see below) |

Note the inconsistency: receipts/inventory/preferences responses are wrapped in
`ApiResponse<T> {success, data, error}`; nutrition/spending/dashboard return raw DTOs directly.
`NutritionController` itself is now split down the middle — `/summary` and `/log` are raw,
`/calendar` is wrapped — since `/calendar` was added after `preference` established the
`ApiResponse` pattern for new endpoints. There still isn't a project-wide convention — match
whatever the specific endpoint you're touching already does.

`/api/nutrition/calendar` also doesn't take a `userId`, unlike every other nutrition/spending
endpoint — `NutritionService.getDailyBreakdown` queries all `nutrition_log` rows in the date range
across all users, not just one. Fine for a single-user app in practice, but a real gap if
multi-user support ever happens.

`/api/recipes/search` deliberately has no `userId` either (decided during Phase 4) — the app is
single-tenant in practice today, so `RecipeService` doesn't scope by user. `/api/nutrition/log-recipe`
has the same gap for the same reason — no `userId` in the request, so those `nutrition_log` rows
are saved with `user_id = null` (see `docs/database.md`).

### `rankBy=mealHistory` (Phase 5)

With `rankBy=mealHistory`, `RecipeService` delegates ordering to
`RecipeRankingService.rankByMealHistorySimilarity`, which POSTs the candidate recipes plus **all**
`nutrition_log` rows (no `userId`) to `ml-service` `POST /recommendations`. `ml-service` embeds both
with sentence-transformers and returns the candidates scored by cosine similarity; results come back
ordered by that score. If `ml-service` is unreachable, errors, or doesn't answer within
`MlServiceClient`'s 5-second timeout, the search **falls back to the default fewest-ingredients
ordering** — it does not 502 or hang. Any other `rankBy` value is treated as "not set". The score
itself is not exposed in the response — only the order changes. See `docs/ml-service.md`.

## Error handling

`GlobalExceptionHandler` (`common/exception`) is a `@RestControllerAdvice` with two handlers:

- `NutritionApiException` (thrown by `NutritionApiClient` on a USDA 5xx or 429) → `502 Bad Gateway`,
  `ApiResponse.fail(message)`
- Everything else (`Exception.class`) → `500`, `ApiResponse.fail(message)`

So even unexpected failures come back as structured JSON, not a raw stack trace — but note this
means **all** unhandled exceptions anywhere in the app currently surface as 500 with the raw
`e.getMessage()`, which could leak internal detail. There's no logging in the handler itself either
— check the application console/log for the actual stack trace, the HTTP response won't have it.

## Receipt upload flow (async, fire-and-forget)

`ReceiptController.uploadReceipt` reads the multipart file synchronously, then hands off to
`ReceiptService.processReceiptAsync`, which chains on `CompletableFuture`:

1. `OcrClient.extractTextFromImage` — base64-encodes the image, POSTs to Gemini with a fixed
   prompt asking for structured JSON, extracts the text from `candidates[0].content.parts[0].text`.
2. `ReceiptParser.parseReceiptText` — strips a ```` ```json ```` markdown fence if present, parses
   with Jackson, accepts either a bare array or `{"items": [...]}`, builds `InventoryItem` rows
   (defaults: quantity `1`, price `0` if missing).
3. `InventoryRepository.saveAll(items)`.

**The HTTP response returns immediately** ("Receipt uploaded and processing in background") before
any of this runs — there is no status endpoint, webhook, or polling mechanism to find out whether
OCR/parsing actually succeeded. Failures anywhere in the chain are caught by `.exceptionally(...)`
and only printed to stderr; the client has no way to know. See `docs/known-issues.md`.

## Nutrition lookup + caching

See `docs/architecture.md` ("Nutrition log → summary") for the flow and `docs/third-party-integrations.md`
for the USDA client details (pageSize, buffer limits, rate limits, match-quality caveat).

## `common.client` — external HTTP clients

- `NutritionApiClient` — Spring `WebClient` (reactive), calls USDA `/foods/search`.
- `OcrClient` — plain `RestTemplate`, calls Gemini's `generateContent` endpoint, parses the
  response with `json-simple` (not Jackson — different JSON library than `ReceiptParser` uses).
- `MlServiceClient` — Spring `WebClient` (reactive), `POST`s to the local `ml-service`
  `/recommendations` endpoint with a 5-second `.timeout(...)`; `.block()`s for the result.
  DTOs in `common/client/dto/` mirror `ml-service`'s Pydantic schemas. Changed from a
  `RestClient` + `/ping` in Phase 5.

Still two different HTTP client styles (`WebClient`, `RestTemplate`) and two different JSON
libraries (`json-simple`, Jackson) across these classes — there's no shared convention to follow if
you add a fourth.
