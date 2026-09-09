package com.yourname.mealassistant.common.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yourname.mealassistant.common.client.dto.RecommendationRequest;
import com.yourname.mealassistant.common.client.dto.RecommendationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class MlServiceClient {

    private final WebClient webClient;

    public MlServiceClient(@Value("${ml-service.base-url}") String baseUrl) {
        // A bare WebClient.builder() serializes LocalDateTime (NutritionLog.loggedAt) as a numeric
        // array, which ml-service's Pydantic `datetime` fields reject with 422. Register
        // JavaTimeModule and turn timestamp-array output off so `logged_at` goes out as an ISO-8601
        // string, then wire that mapper into the client's JSON codecs.
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(mapper, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(mapper, MediaType.APPLICATION_JSON));
                })
                .build();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(strategies)
                .build();
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
