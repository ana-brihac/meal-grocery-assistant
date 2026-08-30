package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.recipe.dto.RecipeDetailResponse;
import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
import com.yourname.mealassistant.recipe.dto.RecipeUpsertRequest;
import com.yourname.mealassistant.recipe.ranking.RecipeRankingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipeServiceTest {

    @Mock RecipeRepository recipeRepository;
    @Mock RecipeIngredientRepository recipeIngredientRepository;
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

    // ---- create / update ----

    @Test
    void createRecipe_savesRecipeThenIngredientRowsWithRecipeId() {
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(i -> {
            Recipe r = i.getArgument(0);
            r.setId(42L);
            return r;
        });
        when(recipeIngredientRepository.findByRecipeId(42L)).thenReturn(List.of());

        RecipeUpsertRequest req = new RecipeUpsertRequest("Oatmeal", "Cook oats", "app",
                List.of(new RecipeUpsertRequest.IngredientInput("oats", 80.0, "g"),
                        new RecipeUpsertRequest.IngredientInput("milk", 200.0, "ml")));

        RecipeDetailResponse res = service.createRecipe(req);

        assertThat(res.id()).isEqualTo(42L);
        assertThat(res.name()).isEqualTo("Oatmeal");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecipeIngredient>> captor = ArgumentCaptor.forClass(List.class);
        verify(recipeIngredientRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(RecipeIngredient::getRecipeId).containsOnly(42L);
        assertThat(captor.getValue()).extracting(RecipeIngredient::getIngredientName)
                .containsExactly("oats", "milk");
    }

    @Test
    void updateRecipe_replacesIngredientsWholesale() {
        Recipe existing = new Recipe();
        existing.setId(7L);
        existing.setName("Old");

        RecipeIngredient stale = new RecipeIngredient();
        stale.setId(1L);
        stale.setRecipeId(7L);
        stale.setIngredientName("old-ingredient");

        when(recipeRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(recipeRepository.save(any(Recipe.class))).thenAnswer(i -> i.getArgument(0));
        when(recipeIngredientRepository.findByRecipeId(7L)).thenReturn(List.of(stale));

        RecipeUpsertRequest req = new RecipeUpsertRequest("New name", "steps", "app",
                List.of(new RecipeUpsertRequest.IngredientInput("rice", 100.0, "g")));

        RecipeDetailResponse res = service.updateRecipe(7L, req);

        assertThat(existing.getName()).isEqualTo("New name");
        verify(recipeIngredientRepository).deleteAll(List.of(stale));
        verify(recipeIngredientRepository).saveAll(any());
        assertThat(res.name()).isEqualTo("New name");
    }

    @Test
    void updateRecipe_unknownId_throws() {
        when(recipeRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.updateRecipe(999L,
                new RecipeUpsertRequest("x", null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
