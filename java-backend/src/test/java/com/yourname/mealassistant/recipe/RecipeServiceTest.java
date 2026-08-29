package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
import com.yourname.mealassistant.recipe.ranking.RecipeRankingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipeServiceTest {

    @Mock RecipeRepository recipeRepository;
    @Mock RecipeRankingService recipeRankingService;

    @InjectMocks RecipeService service;

    @Test
    void searchRecipes_lowercasesIngredientsBeforeQuerying() {
        Recipe pasta = new Recipe();
        pasta.setName("Tomato Pasta");

        when(recipeRepository.findRecipesMakeableFrom(List.of("tomato", "pasta"))).thenReturn(List.of(pasta));
        when(recipeRankingService.rankRecipes(List.of(pasta), List.of())).thenReturn(List.of(pasta));

        RecipeSearchResponse result = service.searchRecipes(new RecipeSearchRequest(List.of("Tomato", "PASTA")));

        assertThat(result.results()).containsExactly(pasta);
    }

    @Test
    void searchRecipes_noMatches_returnsEmptyResult() {
        when(recipeRepository.findRecipesMakeableFrom(List.of("air"))).thenReturn(List.of());
        when(recipeRankingService.rankRecipes(List.of(), List.of())).thenReturn(List.of());

        RecipeSearchResponse result = service.searchRecipes(new RecipeSearchRequest(List.of("air")));

        assertThat(result.results()).isEmpty();
    }

    @Test
    void searchRecipes_returnsRankingServicesOrder() {
        Recipe a = new Recipe();
        a.setName("A");
        Recipe b = new Recipe();
        b.setName("B");

        when(recipeRepository.findRecipesMakeableFrom(List.of("egg"))).thenReturn(List.of(a, b));
        // Ranking service reorders b before a
        when(recipeRankingService.rankRecipes(List.of(a, b), List.of())).thenReturn(List.of(b, a));

        RecipeSearchResponse result = service.searchRecipes(new RecipeSearchRequest(List.of("egg")));

        assertThat(result.results()).containsExactly(b, a);
    }

    @Test
    void searchRecipes_rankByMealHistory_usesSimilarityRanking() {
        Recipe pasta = new Recipe();
        pasta.setName("Tomato Pasta");

        when(recipeRepository.findRecipesMakeableFrom(List.of("tomato"))).thenReturn(List.of(pasta));
        when(recipeRankingService.rankByMealHistorySimilarity(List.of(pasta))).thenReturn(List.of(pasta));

        RecipeSearchResponse result = service.searchRecipes(new RecipeSearchRequest(List.of("tomato"), "mealHistory"));

        assertThat(result.results()).containsExactly(pasta);
    }

    @Test
    void searchRecipes_rankByMealHistory_fallsBackToIngredientRankingOnFailure() {
        Recipe pasta = new Recipe();
        pasta.setName("Tomato Pasta");

        when(recipeRepository.findRecipesMakeableFrom(List.of("tomato"))).thenReturn(List.of(pasta));
        when(recipeRankingService.rankByMealHistorySimilarity(List.of(pasta)))
                .thenThrow(new RuntimeException("ml-service unreachable"));
        when(recipeRankingService.rankRecipes(List.of(pasta), List.of())).thenReturn(List.of(pasta));

        RecipeSearchResponse result = service.searchRecipes(new RecipeSearchRequest(List.of("tomato"), "mealHistory"));

        assertThat(result.results()).containsExactly(pasta);
    }
}
