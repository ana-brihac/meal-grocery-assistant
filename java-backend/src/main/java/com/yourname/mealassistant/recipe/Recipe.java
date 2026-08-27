package com.yourname.mealassistant.recipe;

import jakarta.persistence.*;

// db/init/001_init_schema.sql already defines a `recipes` table (id, name, instructions,
// created_at). Decided: reuse/extend that table (see db/init/003_recipes.sql, which ALTERs it to
// add `source`) rather than introducing a separately-named table — no prep time field, so this
// entity is close to what already exists.
// TODO: `meals` (also in 001_init_schema.sql, meals.recipe_id -> recipes(id)) isn't wired into
// this feature yet — not needed for Phase 4, revisit when meal planning is built.
@Entity
@Table(name = "recipes")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    private String source;

    // Ingredients are modeled via a separate RecipeIngredient entity (decided — see
    // recipe/RecipeIngredient.java), added one at a time from the UI, each with its own
    // quantity/unit. Needed by RecipeRankingService's match scoring and
    // NutritionService.logRecipe's serving-scaled nutrition sum.

    public Recipe() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
