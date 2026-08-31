package com.yourname.mealassistant.recipe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    // A recipe matches if it has at least one ingredient, and every one of its ingredients is
    // contained in the given ingredientNames list ("what can I cook with what I have"). The
    // EXISTS clause guards against a recipe with zero ingredient rows otherwise vacuously
    // matching everything under NOT EXISTS. ingredientNames must already be lower-cased by the
    // caller (see RecipeService) — LOWER() can't be applied per-element to an IN-list parameter.
    @Query("SELECT r FROM Recipe r WHERE "
            + "EXISTS (SELECT ri FROM RecipeIngredient ri WHERE ri.recipeId = r.id) AND "
            + "NOT EXISTS (SELECT ri FROM RecipeIngredient ri WHERE ri.recipeId = r.id "
            + "AND LOWER(ri.ingredientName) NOT IN :ingredientNames)")
    List<Recipe> findRecipesMakeableFrom(@Param("ingredientNames") List<String> ingredientNames);
}
