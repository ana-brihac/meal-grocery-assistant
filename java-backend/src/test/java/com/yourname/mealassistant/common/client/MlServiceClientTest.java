package com.yourname.mealassistant.common.client;

import com.sun.net.httpserver.HttpServer;
import com.yourname.mealassistant.common.client.dto.RecommendationRequest;
import com.yourname.mealassistant.common.client.dto.RecommendationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Uses a real, locally-bound com.sun.net.httpserver.HttpServer instead of mocking WebClient's
// fluent chain — exercises the real HTTP call, JSON serialization/deserialization, and the
// configured timeout, without adding a new test dependency (no wiremock/mockwebserver in the pom).
class MlServiceClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getRecommendations_parsesRealResponse() throws IOException {
        String responseBody = "{\"results\":[{\"id\":1,\"name\":\"Chicken Stir Fry\",\"score\":0.82}]}";

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/recommendations", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        MlServiceClient client = new MlServiceClient("http://localhost:" + server.getAddress().getPort());
        RecommendationResponse response = client.getRecommendations(new RecommendationRequest(List.of(), List.of()));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getId()).isEqualTo(1L);
        assertThat(response.getResults().get(0).getName()).isEqualTo("Chicken Stir Fry");
        assertThat(response.getResults().get(0).getScore()).isEqualTo(0.82);
    }

    // ml-service's Pydantic schema types `logged_at` as `datetime`, which accepts an ISO-8601
    // string but rejects the numeric array Jackson emits for LocalDateTime by default (that path
    // 422s and the caller silently falls back to the non-ML ordering). This pins the request body
    // to the ISO-8601 form.
    @Test
    void getRecommendations_serializesLoggedAtAsIso8601String() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/recommendations", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        MlServiceClient client = new MlServiceClient("http://localhost:" + server.getAddress().getPort());
        // Mirror the shape RecipeRankingService actually sends: candidates with nested ingredient
        // rows, plus a meal-history entry carrying a LocalDateTime.
        RecommendationRequest.RecipeCandidate candidate = new RecommendationRequest.RecipeCandidate(
                20L, "Pasta with Tomato Sauce",
                List.of(new RecommendationRequest.RecipeIngredient("pasta", 120.0, "g"),
                        new RecommendationRequest.RecipeIngredient("tomato sauce", 200.0, "g")));
        RecommendationRequest.MealHistoryEntry entry = new RecommendationRequest.MealHistoryEntry(
                "pasta", LocalDateTime.of(2026, 9, 3, 18, 55, 19));
        client.getRecommendations(new RecommendationRequest(List.of(candidate), List.of(entry)));

        assertThat(requestBody.get()).contains("\"logged_at\":\"2026-09-03T18:55:19\"");
        assertThat(requestBody.get()).doesNotContain("[2026,9,3");
        assertThat(requestBody.get()).contains("\"meal_history\"");
    }

    // Verifies MlServiceClient's configured 5s timeout actually fires instead of hanging
    // indefinitely — this test takes ~5s to run since it waits out the real timeout.
    @Test
    void getRecommendations_timesOutWhenMlServiceHangs() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/recommendations", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        MlServiceClient client = new MlServiceClient("http://localhost:" + server.getAddress().getPort());

        assertThatThrownBy(() -> client.getRecommendations(new RecommendationRequest(List.of(), List.of())))
                .isInstanceOf(RuntimeException.class);
    }
}
