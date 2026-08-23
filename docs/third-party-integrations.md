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
- Caching: results are cached by normalized item name in `nutrition_info` (see `docs/database.md`),
  including a null-macro row when there's no match, so a given name is never looked up twice.

## Gemini (receipt OCR)

- Client: `common/client/OcrClient.java` (plain `RestTemplate`).
- Config: `ocr.api-url` / `ocr.api-key` in `application.yml`, key comes from env var
  `OCR_API_KEY`. Endpoint is hardcoded to
  `gemini-flash-latest:generateContent` (`generativelanguage.googleapis.com`).
- Request: image is base64-encoded and sent inline (`inline_data`) alongside a fixed prompt asking
  Gemini to extract items/quantities/prices as structured JSON. No streaming, no retries.
- Response parsing: `OcrClient.parse` manually walks `candidates[0].content.parts[0].text` using
  `json-simple` (not Jackson — note this is a different JSON library than `ReceiptParser` uses one
  step later, which uses Jackson's `ObjectMapper`). Throws a `RuntimeException` if the shape is
  unexpected — this propagates up through the async chain and is only logged to stderr (see
  `docs/backend-api.md`, receipt upload flow).
- Gemini's text output isn't guaranteed valid JSON — it commonly wraps it in a ` ```json ` markdown
  fence, which `ReceiptParser` strips with a regex before parsing. If Gemini changes its output
  format, this is the first place to look.

## ml-service (internal, not third-party, but similar integration shape)

The backend also calls its own `ml-service` over plain HTTP (`MlServiceClient`, Spring
`RestClient`), currently only for a `/ping` passthrough. See `docs/ml-service.md` — it's a scaffold,
not a real integration yet.
