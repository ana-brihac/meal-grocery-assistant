package com.yourname.mealassistant.common.client;

import com.yourname.mealassistant.common.client.dto.UsdaSearchResponse;
import com.yourname.mealassistant.common.exception.NutritionApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class NutritionApiClient {
    
    private final WebClient webClient;

    @Value("${usda.api-key}")
    private String apiKey;

    public NutritionApiClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public UsdaSearchResponse searchFoodByName(String foodName) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/foods/search")
                .queryParam("query", foodName)
                .queryParam("pageSize", 5)
                .queryParam("api_key", apiKey)
                .build())
            .retrieve()
            .onStatus(status -> status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS), 
                response -> Mono.error(new NutritionApiException("USDA API Rate Limit Exceeded!")))
            .onStatus(status -> status.is5xxServerError(), 
                response -> Mono.error(new NutritionApiException("USDA API is currently unavailable.")))
            .bodyToMono(UsdaSearchResponse.class)
            .timeout(Duration.ofSeconds(5))
            .block();
    }
}
