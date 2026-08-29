package com.yourname.mealassistant.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

// Mirrors ml-service's RecommendationRequest schema (ml-service/app/schemas/recommendation_schemas.py).
// ml-service has no DB access of its own, so candidates carry pre-assembled ingredient data rather
// than a raw entity reference.
public class RecommendationRequest {

    private List<RecipeCandidate> candidates;

    @JsonProperty("meal_history")
    private List<MealHistoryEntry> mealHistory;

    public RecommendationRequest() {}

    public RecommendationRequest(List<RecipeCandidate> candidates, List<MealHistoryEntry> mealHistory) {
        this.candidates = candidates;
        this.mealHistory = mealHistory;
    }

    public List<RecipeCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<RecipeCandidate> candidates) { this.candidates = candidates; }

    public List<MealHistoryEntry> getMealHistory() { return mealHistory; }
    public void setMealHistory(List<MealHistoryEntry> mealHistory) { this.mealHistory = mealHistory; }

    public static class RecipeCandidate {
        private Long id;
        private String name;
        private List<RecipeIngredient> ingredients;

        public RecipeCandidate() {}

        public RecipeCandidate(Long id, String name, List<RecipeIngredient> ingredients) {
            this.id = id;
            this.name = name;
            this.ingredients = ingredients;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public List<RecipeIngredient> getIngredients() { return ingredients; }
        public void setIngredients(List<RecipeIngredient> ingredients) { this.ingredients = ingredients; }
    }

    // Mirrors java-backend's own RecipeIngredient (recipe/RecipeIngredient.java) — name/quantity/unit.
    public static class RecipeIngredient {
        private String name;
        private Double quantity;
        private String unit;

        public RecipeIngredient() {}

        public RecipeIngredient(String name, Double quantity, String unit) {
            this.name = name;
            this.quantity = quantity;
            this.unit = unit;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Double getQuantity() { return quantity; }
        public void setQuantity(Double quantity) { this.quantity = quantity; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
    }

    public static class MealHistoryEntry {
        private String name;

        @JsonProperty("logged_at")
        private LocalDateTime loggedAt;

        public MealHistoryEntry() {}

        public MealHistoryEntry(String name, LocalDateTime loggedAt) {
            this.name = name;
            this.loggedAt = loggedAt;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public LocalDateTime getLoggedAt() { return loggedAt; }
        public void setLoggedAt(LocalDateTime loggedAt) { this.loggedAt = loggedAt; }
    }
}
