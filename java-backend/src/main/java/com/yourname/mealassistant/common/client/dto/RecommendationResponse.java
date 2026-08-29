package com.yourname.mealassistant.common.client.dto;

import java.util.List;

// Mirrors ml-service's RecommendationResponse schema (ml-service/app/schemas/recommendation_schemas.py).
public class RecommendationResponse {

    private List<RecipeScore> results;

    public RecommendationResponse() {}

    public List<RecipeScore> getResults() { return results; }
    public void setResults(List<RecipeScore> results) { this.results = results; }

    public static class RecipeScore {
        private Long id;
        private String name;
        private Double score;

        public RecipeScore() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
    }
}
