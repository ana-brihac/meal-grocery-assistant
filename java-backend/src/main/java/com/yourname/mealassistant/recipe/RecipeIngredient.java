package com.yourname.mealassistant.recipe;

import jakarta.persistence.*;

// Decided: ingredients are modeled as a separate entity (not an @ElementCollection on Recipe),
// added one at a time from the UI, each with its own quantity/unit.
@Entity
@Table(name = "recipe_ingredients")
public class RecipeIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Decided: raw FK Long (matching NutritionLog.userId's style), not a @ManyToOne Recipe
    // reference.
    @Column(name = "recipe_id")
    private Long recipeId;

    @Column(name = "ingredient_name")
    private String ingredientName;

    private Double quantity;

    private String unit;

    public RecipeIngredient() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecipeId() {
        return recipeId;
    }

    public void setRecipeId(Long recipeId) {
        this.recipeId = recipeId;
    }

    public String getIngredientName() {
        return ingredientName;
    }

    public void setIngredientName(String ingredientName) {
        this.ingredientName = ingredientName;
    }

    public Double getQuantity() {
        return quantity;
    }

    public void setQuantity(Double quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}
