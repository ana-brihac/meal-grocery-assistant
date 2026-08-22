package com.yourname.mealassistant.nutrition.dto;

public class NutritionSummaryResponse {
    private Double totalCalories = 0.0;
    private Double totalProtein = 0.0;
    private Double totalCarbs = 0.0;
    private Double totalFats = 0.0;
    private Double totalFibers = 0.0;

    public Double getTotalCalories() { return totalCalories; }
    public void addCalories(Double calories) { this.totalCalories += calories; }

    public Double getTotalProtein() { return totalProtein; }
    public void addProtein(Double protein) { this.totalProtein += protein; }

    public Double getTotalCarbs() { return totalCarbs; }
    public void addCarbs(Double carbs) { this.totalCarbs += carbs; }

    public Double getTotalFats() { return totalFats; }
    public void addFats(Double fats) { this.totalFats += fats; }

    public Double getTotalFibers() { return totalFibers; }
    public void addFibers(Double fibers) { this.totalFibers += fibers; }
}
