# Third-party integrations

## USDA FoodData Central (nutrition lookup)

- Client: `common/client/NutritionApiClient.java` (Spring `WebClient`, reactive, `.block()`ed to
  stay synchronous from the caller's perspective).
- Config: `usda.api-url` / `usda.api-key` in `application.yml`, key comes from env var
  `USDA_API_KEY`. Sign up at https://fdc.nal.usda.gov/api-key-signup, or use the public `DEMO_KEY`
  for light testing.
- Endpoint used: `GET /foods/search?query={name}&pageSize=5&api_key={key}`.
- `pageSize=5` is deliberate — the endpoint's default page size is 50, and USDA's branded-food
  results can be large enough (long ingredient lists etc.) to blow past Spring's default 256KB
  WebClient in-memory buffer, causing a `DataBufferLimitException` on ordinary queries like
  "banana". `WebClientConfig` also raises the buffer to 2MB as a backstop. If you see that
  exception again, something is pulling a much bigger page than expected.
- Error handling: `NutritionApiClient` maps USDA `429` and `5xx` to `NutritionApiException`, which
  `GlobalExceptionHandler` turns into a `502`. Anything else (network error, bad JSON, timeout —
  there's a 5s timeout on the call) falls through to the generic `500` handler.
- **Match quality caveat**: `NutritionService.getOrFetchNutritionInfo` just takes
  `searchResponse.getFoods().get(0)` — the first hit, with no filtering by `dataType`. USDA mixes
  `Foundation`/`SR Legacy` (generic reference data) with `Branded` (specific packaged products) in
  search results, ranked by their own relevance score, not data quality. In practice this means a
  query like "banana" can match a branded peanut-butter spread instead of a raw banana, giving
  plausible-looking but wrong macros. There's no code-level flag for "this data might be off" —
  if nutrition numbers look implausible, check `nutrition_info.item_name`'s cached row and compare
  against what USDA's `/foods/search` actually returns for that query before assuming a bug
  elsewhere. See `docs/known-issues.md`.
- Caching: results are cached by normalized item name in `nutrition_info` (see `docs/database.md`).
  On a USDA miss the lookup then tries the Gemini fallback below; if that's also empty, a
  null-macro row is still cached so the name is never looked up twice. Delete null-macro rows to
  force a re-fetch (e.g. after fixing a bad key).

## Gemini (receipt OCR, price-tag OCR, nutrition fallback)

Three uses, all against the same `generativelanguage.googleapis.com`
`gemini-flash-latest:generateContent` endpoint, all keyed by `ocr.api-key` /
env var `OCR_API_KEY` (the `ocr.*` config names predate the other two uses).

- **`common/client/OcrClient.java`** (plain `RestTemplate`, response parsed with `json-simple`).
  `extractTextFromImage(bytes, contentType)` sends the image inline (`inline_data`) with a fixed
  receipt prompt; the `(bytes, contentType, prompt)` overload takes a caller-supplied prompt and
  is used for shelf price tags (`POST /api/prices/from-photo` → `IngredientPriceService`). Walks
  `candidates[0].content.parts[0].text`; throws `RuntimeException` on an unexpected shape.
- **`common/client/NutritionAiClient.java`** (plain `RestTemplate` + Jackson). Text-only call —
  no image — asking for a food's per-100g macros as a JSON object. Called by
  `NutritionService.getOrFetchNutritionInfo` only when USDA returns no calories. Never throws: a
  failed call just leaves the food's macros null.
- Gemini's text output isn't guaranteed valid JSON — it commonly wraps it in a ` ```json ` markdown
  fence, stripped with a regex before parsing (`ReceiptParser` for receipts, the client itself for
  price tags / nutrition). If Gemini changes its output format, that's the first place to look.
- If Google flags the key as leaked and disables it, every Gemini-backed feature 403s. Rotate the
  key; nutrition lookups that were attempted while it was dead are cached null and need their rows
  deleted to retry.

## ml-service (internal, not third-party, but similar integration shape)

The backend calls its own `ml-service` over plain HTTP via `MlServiceClient` (Spring `WebClient`,
5s timeout) for `POST /recommendations`, used by `rankBy=mealHistory` recipe search. See
`docs/ml-service.md`.
