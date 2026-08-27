package com.yourname.mealassistant.nutrition.dto;

public class LogRecipeRequest {
    private Long recipeId;
    private Double servings;

    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }

    public Double getServings() { return servings; }
    public void setServings(Double servings) { this.servings = servings; }
}
