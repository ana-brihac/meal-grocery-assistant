package com.yourname.mealassistant.recipe.ranking;

import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipeRankingServiceTest {

    @Mock RecipeIngredientRepository recipeIngredientRepository;

    @InjectMocks RecipeRankingService service;

    @Test
    void rankRecipes_ordersByFewestIngredientsFirst() {
        Recipe simple = new Recipe();
        simple.setId(1L);
        simple.setName("Simple");

        Recipe complex = new Recipe();
        complex.setId(2L);
        complex.setName("Complex");

        when(recipeIngredientRepository.countByRecipeId(1L)).thenReturn(2L);
        when(recipeIngredientRepository.countByRecipeId(2L)).thenReturn(5L);

        List<Recipe> ranked = service.rankRecipes(List.of(complex, simple), List.of());

        assertThat(ranked).containsExactly(simple, complex);
    }

    @Test
    void rankRecipes_emptyCandidates_returnsEmptyList() {
        List<Recipe> ranked = service.rankRecipes(List.of(), List.of());

        assertThat(ranked).isEmpty();
    }
}
