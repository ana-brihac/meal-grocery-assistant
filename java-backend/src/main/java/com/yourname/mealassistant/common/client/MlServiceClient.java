package com.yourname.mealassistant.common.client;

import com.yourname.mealassistant.common.client.dto.RecommendationRequest;
import com.yourname.mealassistant.common.client.dto.RecommendationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class MlServiceClient {

    private final WebClient webClient;

    public MlServiceClient(@Value("${ml-service.base-url}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public RecommendationResponse getRecommendations(RecommendationRequest request) {
        return webClient.post()
                .uri("/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RecommendationResponse.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }
}
