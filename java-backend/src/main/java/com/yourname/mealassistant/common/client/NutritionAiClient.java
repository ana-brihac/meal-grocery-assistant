package com.yourname.mealassistant.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.mealassistant.common.client.dto.NutritionEstimate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

// Last-resort nutrition source: when the USDA lookup returns no usable macros for a
// food, ask Gemini for typical per-100g values. Expected to be rare (most foods resolve via the
// nutrition_info cache or USDA), so the per-call cost is acceptable.
//
// Reuses the same Gemini generateContent endpoint + key as OcrClient — the `ocr.*` properties
// are really "the Gemini config", they just got named for the first feature that used them. This
// is a text-only call (no image part), otherwise the request/response shape matches OcrClient.
@Component
public class NutritionAiClient {

    @Value("${ocr.api-url}")
    private String apiUrl;

    @Value("${ocr.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Returns null if the call fails, the response can't be parsed, or the model doesn't
    // recognise the food (all-null values). Never throws — a failed estimate just means the
    // caller keeps the food's macros null, same as a USDA miss.
    public NutritionEstimate estimateNutrition(String foodName) {
        String prompt = "Give typical nutrition values per 100 grams of the edible portion of the food: \""
                + foodName + "\". "
                + "Respond with ONLY a JSON object of the form "
                + "{\"calories\": number, \"protein\": number, \"fiber\": number, \"fat\": number, \"carbs\": number} "
                + "- calories in kcal, the rest in grams. "
                + "If you do not recognise the food, use null for every value.";

        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String rawResponse = restTemplate.postForObject(
                    apiUrl + "?key=" + apiKey, new HttpEntity<>(requestBody, headers), String.class);

            String modelText = objectMapper.readTree(rawResponse)
                    .path("candidates").path(0).path("content").path("parts").path(0).path("text")
                    .asText("");

            String cleanJson = modelText.replaceAll("(?s)```(?:json)?\\s*(.*?)\\s*```", "$1").trim();
            JsonNode node = objectMapper.readTree(cleanJson);

            NutritionEstimate estimate = new NutritionEstimate(
                    number(node, "calories"), number(node, "protein"), number(node, "fiber"),
                    number(node, "fat"), number(node, "carbs"));

            return estimate.calories() == null ? null : estimate;
        } catch (Exception e) {
            System.err.println("AI nutrition estimate failed for '" + foodName + "': " + e.getMessage());
            return null;
        }
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }
}
