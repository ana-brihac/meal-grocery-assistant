package com.yourname.mealassistant.common.client;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.io.IOException;

@Component
public class OcrClient {

    private static final String RECEIPT_PROMPT =
        "Extract the items, quantities, and prices from this receipt into a structured JSON format.";

    @Value("${ocr.api-key}")
    private String apiKey;

    @Value("${ocr.api-url}")
    private String apiUrl;

    public String extractTextFromImage(byte[] fileBytes, String contentType) throws IOException {
        return extractTextFromImage(fileBytes, contentType, RECEIPT_PROMPT);
    }

    // Same call, caller-supplied prompt — used by IngredientPriceService for shelf price tags
    //, which need a different instruction than the receipt one.
    public String extractTextFromImage(byte[] fileBytes, String contentType, String prompt) throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(fileBytes);

        if (contentType == null || contentType.isEmpty()) {
            contentType = "image/jpeg"; // Default fallback
        }

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of(
                    "parts", List.of(
                        Map.of("text", prompt),
                        Map.of("inline_data", Map.of(
                            "mime_type", contentType,
                            "data", base64Image
                        ))
                    )
                )
            )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        String fullUrl = apiUrl + "?key=" + apiKey;
        String rawJsonResponse = restTemplate.postForObject(fullUrl, requestEntity, String.class);

        return parse(rawJsonResponse);
    }

    private String parse(String rawJsonResponse) {
        Object obj = JSONValue.parse(rawJsonResponse);
        JSONObject rootObject = (JSONObject) obj;

        JSONArray candidates = (JSONArray) rootObject.get("candidates");

        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("No candidates found in OCR response");
        }

        JSONObject firstCandidate = (JSONObject) candidates.get(0);

        JSONObject content = (JSONObject) firstCandidate.get("content");

        JSONArray parts = (JSONArray) content.get("parts");

        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("No parts found in OCR response content");
        }

        JSONObject firstPart = (JSONObject) parts.get(0);

        return (String) firstPart.get("text");
    }
}