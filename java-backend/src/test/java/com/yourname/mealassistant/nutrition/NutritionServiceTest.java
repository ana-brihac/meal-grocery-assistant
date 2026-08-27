package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.client.NutritionApiClient;
import com.yourname.mealassistant.nutrition.dto.DailyNutritionSummary;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredient;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import com.yourname.mealassistant.recipe.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NutritionServiceTest {

    @Mock NutritionApiClient nutritionApiClient;
    @Mock NutritionInfoRepository nutritionInfoRepository;
    @Mock NutritionLogRepository nutritionLogRepository;
    @Mock RecipeRepository recipeRepository;
    @Mock RecipeIngredientRepository recipeIngredientRepository;

    @InjectMocks NutritionService service;

    private NutritionInfo cachedChicken;

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO   = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

    @BeforeEach
    void setUp() {
        cachedChicken = new NutritionInfo();
        cachedChicken.setItemName("chicken");
        cachedChicken.setBaseQuantity(100.0);
        cachedChicken.setCalories(165.0);
        cachedChicken.setProtein(31.0);
        cachedChicken.setCarbs(0.0);
        cachedChicken.setFats(3.6);
        cachedChicken.setFibers(0.0);
    }

    // ---- getOrFetchNutritionInfo ----

    @Test
    void getOrFetchNutritionInfo_cacheHit_doesNotCallApi() {
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        NutritionInfo result = service.getOrFetchNutritionInfo("chicken");

        assertThat(result).isEqualTo(cachedChicken);
        verify(nutritionApiClient, never()).searchFoodByName(any());
    }

    @Test
    void getOrFetchNutritionInfo_cacheMiss_callsApiAndSaves() {
        when(nutritionInfoRepository.findById("tuna")).thenReturn(Optional.empty());
        // Return null response to simulate an API call with no food data
        when(nutritionApiClient.searchFoodByName("tuna")).thenReturn(null);
        when(nutritionInfoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NutritionInfo result = service.getOrFetchNutritionInfo("tuna");

        verify(nutritionApiClient, times(1)).searchFoodByName("tuna");
        verify(nutritionInfoRepository, times(1)).save(any(NutritionInfo.class));
        assertThat(result.getItemName()).isEqualTo("tuna");
    }

    // ---- logMeal ----

    @Test
    void logMeal_savesLogEntryToRepository() {
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        service.logMeal(1L, "chicken", 200.0);

        verify(nutritionLogRepository, times(1)).save(any(NutritionLog.class));
    }

    // ---- logRecipe ----

    @Test
    void logRecipe_savesOneLogEntryPerIngredientScaledByServingsAndTaggedWithRecipeId() {
        Recipe recipe = new Recipe();
        recipe.setId(10L);
        recipe.setName("Tomato Pasta");

        RecipeIngredient pasta = new RecipeIngredient();
        pasta.setIngredientName("pasta");
        pasta.setQuantity(200.0);

        RecipeIngredient oil = new RecipeIngredient();
        oil.setIngredientName("oil");
        oil.setQuantity(15.0);

        NutritionInfo pastaInfo = new NutritionInfo();
        pastaInfo.setItemName("pasta");
        pastaInfo.setBaseQuantity(100.0);

        NutritionInfo oilInfo = new NutritionInfo();
        oilInfo.setItemName("oil");
        oilInfo.setBaseQuantity(100.0);

        when(recipeRepository.findById(10L)).thenReturn(Optional.of(recipe));
        when(recipeIngredientRepository.findByRecipeId(10L)).thenReturn(List.of(pasta, oil));
        when(nutritionInfoRepository.findById("pasta")).thenReturn(Optional.of(pastaInfo));
        when(nutritionInfoRepository.findById("oil")).thenReturn(Optional.of(oilInfo));

        service.logRecipe(10L, 2.0);

        ArgumentCaptor<NutritionLog> captor = ArgumentCaptor.forClass(NutritionLog.class);
        verify(nutritionLogRepository, times(2)).save(captor.capture());

        List<NutritionLog> saved = captor.getAllValues();
        assertThat(saved).allMatch(log -> log.getRecipeId().equals(10L));
        assertThat(saved).anyMatch(log -> log.getItemName().equals("pasta") && log.getQuantityGrams().equals(400.0));
        assertThat(saved).anyMatch(log -> log.getItemName().equals("oil") && log.getQuantityGrams().equals(30.0));
    }

    @Test
    void logRecipe_unknownRecipeId_throws() {
        when(recipeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.logRecipe(999L, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- getDailyBreakdown ----

    @Test
    void getDailyBreakdown_recipeSourcedLog_populatesRecipeIdAndName() {
        Recipe recipe = new Recipe();
        recipe.setId(10L);
        recipe.setName("Tomato Pasta");

        NutritionLog log = new NutritionLog();
        log.setItemName("pasta");
        log.setQuantityGrams(400.0);
        log.setRecipeId(10L);
        log.setLoggedAt(LocalDateTime.of(2026, 8, 15, 12, 0));

        NutritionInfo pastaInfo = new NutritionInfo();
        pastaInfo.setItemName("pasta");
        pastaInfo.setBaseQuantity(100.0);
        pastaInfo.setCalories(200.0);

        when(nutritionLogRepository.findAll()).thenReturn(List.of(log));
        when(nutritionInfoRepository.findById("pasta")).thenReturn(Optional.of(pastaInfo));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(recipe));

        List<DailyNutritionSummary> result = service.getDailyBreakdown(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 15));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entries()).hasSize(1);
        assertThat(result.get(0).entries().get(0).recipeId()).isEqualTo(10L);
        assertThat(result.get(0).entries().get(0).recipeName()).isEqualTo("Tomato Pasta");
    }

    // ---- getSummary ----

    @Test
    void getSummary_noLogs_returnsZeroTotals() {
        when(nutritionLogRepository.findByUserIdAndLoggedAtBetween(1L, FROM, TO)).thenReturn(List.of());

        NutritionSummaryResponse result = service.getSummary(1L, FROM, TO);

        assertThat(result.getTotalCalories()).isEqualTo(0.0);
        assertThat(result.getTotalProtein()).isEqualTo(0.0);
    }

    @Test
    void getSummary_withLogs_calculatesCorrectMacros() {
        // User ate 200g of chicken (2x the 100g base)
        NutritionLog log = new NutritionLog();
        log.setUserId(1L);
        log.setItemName("chicken");
        log.setQuantityGrams(200.0);

        when(nutritionLogRepository.findByUserIdAndLoggedAtBetween(1L, FROM, TO)).thenReturn(List.of(log));
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        NutritionSummaryResponse result = service.getSummary(1L, FROM, TO);

        // 200g / 100g = 2x multiplier => 165 * 2 = 330 calories
        assertThat(result.getTotalCalories()).isEqualTo(330.0);
        // 31 * 2 = 62g protein
        assertThat(result.getTotalProtein()).isEqualTo(62.0);
    }
}
