package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.client.NutritionAiClient;
import com.yourname.mealassistant.common.client.NutritionApiClient;
import com.yourname.mealassistant.common.client.dto.NutritionEstimate;
import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.nutrition.dto.DailyNutritionSummary;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.nutrition.dto.RecipeNutrition;
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
    @Mock NutritionAiClient nutritionAiClient;
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

    @Test
    void getOrFetchNutritionInfo_usdaMiss_fallsBackToAiEstimateAndCachesIt() {
        when(nutritionInfoRepository.findById("dragonfruit")).thenReturn(Optional.empty());
        when(nutritionApiClient.searchFoodByName("dragonfruit")).thenReturn(null);
        when(nutritionAiClient.estimateNutrition("dragonfruit"))
                .thenReturn(new NutritionEstimate(60.0, 1.2, 3.0, 0.4, 13.0));
        when(nutritionInfoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NutritionInfo result = service.getOrFetchNutritionInfo("dragonfruit");

        assertThat(result.getCalories()).isEqualTo(60.0);
        assertThat(result.getProtein()).isEqualTo(1.2);
        assertThat(result.getFibers()).isEqualTo(3.0);
        verify(nutritionInfoRepository, times(1)).save(any(NutritionInfo.class));
    }

    // ---- computeRecipeNutrition ----

    @Test
    void computeRecipeNutrition_sumsIngredientMacrosScaledByServings() {
        Recipe recipe = new Recipe();
        recipe.setId(10L);

        RecipeIngredient pasta = new RecipeIngredient();
        pasta.setIngredientName("pasta");
        pasta.setQuantity(100.0);

        NutritionInfo pastaInfo = new NutritionInfo();
        pastaInfo.setItemName("pasta");
        pastaInfo.setBaseQuantity(100.0);
        pastaInfo.setCalories(350.0);
        pastaInfo.setProtein(12.0);
        pastaInfo.setFibers(3.0);

        when(recipeRepository.findById(10L)).thenReturn(Optional.of(recipe));
        when(recipeIngredientRepository.findByRecipeId(10L)).thenReturn(List.of(pasta));
        when(nutritionInfoRepository.findById("pasta")).thenReturn(Optional.of(pastaInfo));

        RecipeNutrition n = service.computeRecipeNutrition(10L, 2.0);

        // 100g * 2 servings = 200g -> 2x the 100g base
        assertThat(n.calories()).isEqualTo(700.0);
        assertThat(n.protein()).isEqualTo(24.0);
        assertThat(n.fiber()).isEqualTo(6.0);
        assertThat(n.complete()).isTrue();
    }

    @Test
    void computeRecipeNutrition_flagsIncompleteWhenAnIngredientHasNoCalories() {
        Recipe recipe = new Recipe();
        recipe.setId(11L);

        RecipeIngredient known = new RecipeIngredient();
        known.setIngredientName("rice");
        known.setQuantity(100.0);

        RecipeIngredient mystery = new RecipeIngredient();
        mystery.setIngredientName("mystery spice");
        mystery.setQuantity(5.0);

        NutritionInfo riceInfo = new NutritionInfo();
        riceInfo.setItemName("rice");
        riceInfo.setBaseQuantity(100.0);
        riceInfo.setCalories(360.0);

        NutritionInfo mysteryInfo = new NutritionInfo();
        mysteryInfo.setItemName("mystery spice");
        mysteryInfo.setBaseQuantity(100.0);
        // no calories -> even after USDA/AI it's still unknown

        when(recipeRepository.findById(11L)).thenReturn(Optional.of(recipe));
        when(recipeIngredientRepository.findByRecipeId(11L)).thenReturn(List.of(known, mystery));
        when(nutritionInfoRepository.findById("rice")).thenReturn(Optional.of(riceInfo));
        when(nutritionInfoRepository.findById("mystery spice")).thenReturn(Optional.of(mysteryInfo));

        RecipeNutrition n = service.computeRecipeNutrition(11L, 1.0);

        assertThat(n.calories()).isEqualTo(360.0);
        assertThat(n.complete()).isFalse();
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

        assertThatThrownBy(() -> service.logRecipe(999L, 1.0)).isInstanceOf(NotFoundException.class);
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

    // ---- getDailyBreakdown ----
    //
    // getDailyBreakdown uses nutritionLogRepository.findAll() (not a userId/date-scoped query —
    // the still-open "no userId param" issue in known-issues.md; not fixed here). Every
    // NutritionLog needs setLoggedAt(...) or log.getLoggedAt().toLocalDate() NPEs. Per-entry
    // multiplier is quantityGrams / nutrition_info.base_quantity.

    private static NutritionLog dayLog(String itemName, double grams, LocalDateTime at, Long recipeId) {
        NutritionLog log = new NutritionLog();
        log.setItemName(itemName);
        log.setQuantityGrams(grams);
        log.setLoggedAt(at);
        log.setRecipeId(recipeId);
        return log;
    }

    private static NutritionInfo info(String name, Double base, Double calories, Double protein, Double fibers) {
        NutritionInfo i = new NutritionInfo();
        i.setItemName(name);
        i.setBaseQuantity(base);
        i.setCalories(calories);
        i.setProtein(protein);
        i.setFibers(fibers);
        return i;
    }

    @Test
    void getDailyBreakdown_zeroLogDays_stillReturnedWithZeroTotalsAndEmptyEntries() {
        when(nutritionLogRepository.findAll()).thenReturn(List.of());

        List<DailyNutritionSummary> result =
                service.getDailyBreakdown(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(DailyNutritionSummary::date)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3));
        assertThat(result).allSatisfy(d -> {
            assertThat(d.totalCalories()).isEqualTo(0.0);
            assertThat(d.totalProtein()).isEqualTo(0.0);
            assertThat(d.totalFiber()).isEqualTo(0.0);
            assertThat(d.entries()).isEmpty();
        });
    }

    @Test
    void getDailyBreakdown_singleDayRange_startEqualsEnd_returnsExactlyOneDay() {
        LocalDate d = LocalDate.of(2026, 8, 15);
        when(nutritionLogRepository.findAll())
                .thenReturn(List.of(dayLog("chicken", 200.0, d.atTime(12, 0), null)));
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        List<DailyNutritionSummary> result = service.getDailyBreakdown(d, d);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).date()).isEqualTo(d);
        assertThat(result.get(0).entries()).hasSize(1);
    }

    @Test
    void getDailyBreakdown_multipleLogsSameDay_totalsAreSummedWithPerEntryMultiplier() {
        LocalDate d = LocalDate.of(2026, 8, 15);
        when(nutritionLogRepository.findAll()).thenReturn(List.of(
                dayLog("chicken", 200.0, d.atTime(8, 0), null),
                dayLog("chicken", 50.0, d.atTime(19, 0), null)));
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        DailyNutritionSummary day = service.getDailyBreakdown(d, d).get(0);

        assertThat(day.entries()).hasSize(2);
        // 165 kcal / 31 g protein per 100 g base; 200 g -> 2x, 50 g -> 0.5x
        assertThat(day.totalCalories()).isEqualTo(165 * 2 + 165 * 0.5);
        assertThat(day.totalProtein()).isEqualTo(31 * 2 + 31 * 0.5);
        assertThat(day.entries()).extracting(e -> e.calories()).containsExactly(165 * 2.0, 165 * 0.5);
    }

    @Test
    void getDailyBreakdown_logsOutsideTheRequestedRange_areExcluded() {
        when(nutritionLogRepository.findAll()).thenReturn(List.of(
                dayLog("chicken", 100.0, LocalDate.of(2026, 7, 31).atTime(12, 0), null),
                dayLog("chicken", 100.0, LocalDate.of(2026, 8, 4).atTime(12, 0), null)));

        List<DailyNutritionSummary> result =
                service.getDailyBreakdown(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(d -> {
            assertThat(d.entries()).isEmpty();
            assertThat(d.totalCalories()).isEqualTo(0.0);
        });
    }

    @Test
    void getDailyBreakdown_logWithNoCachedNutritionInfo_isSkippedButDayStillPresent() {
        LocalDate d = LocalDate.of(2026, 8, 15);
        when(nutritionLogRepository.findAll())
                .thenReturn(List.of(dayLog("mystery", 100.0, d.atTime(12, 0), null)));
        when(nutritionInfoRepository.findById("mystery")).thenReturn(Optional.empty());

        DailyNutritionSummary day = service.getDailyBreakdown(d, d).get(0);

        assertThat(day.date()).isEqualTo(d);
        assertThat(day.entries()).isEmpty();
        assertThat(day.totalCalories()).isEqualTo(0.0);
    }

    @Test
    void getDailyBreakdown_nutritionInfoWithNullMacros_contributesZeroNotNpe() {
        LocalDate d = LocalDate.of(2026, 8, 15);
        when(nutritionLogRepository.findAll())
                .thenReturn(List.of(dayLog("water", 500.0, d.atTime(12, 0), null)));
        when(nutritionInfoRepository.findById("water"))
                .thenReturn(Optional.of(info("water", 100.0, null, null, null)));

        DailyNutritionSummary day = service.getDailyBreakdown(d, d).get(0);

        assertThat(day.entries()).hasSize(1);
        assertThat(day.entries().get(0).calories()).isEqualTo(0.0);
        assertThat(day.entries().get(0).protein()).isEqualTo(0.0);
        assertThat(day.entries().get(0).fiber()).isEqualTo(0.0);
        assertThat(day.totalCalories()).isEqualTo(0.0);
    }

    @Test
    void getDailyBreakdown_manuallyLoggedEntry_hasNullRecipeIdAndRecipeName() {
        LocalDate d = LocalDate.of(2026, 8, 15);
        when(nutritionLogRepository.findAll())
                .thenReturn(List.of(dayLog("chicken", 100.0, d.atTime(12, 0), null)));
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        DailyNutritionSummary day = service.getDailyBreakdown(d, d).get(0);

        assertThat(day.entries().get(0).recipeId()).isNull();
        assertThat(day.entries().get(0).recipeName()).isNull();
        verify(recipeRepository, never()).findById(any());
    }

    @Test
    void getDailyBreakdown_sameRecipeIdAcrossMultipleDays_resolvesRecipeNameOnce() {
        Recipe recipe = new Recipe();
        recipe.setId(10L);
        recipe.setName("Tomato Pasta");

        NutritionInfo pastaInfo = info("pasta", 100.0, 200.0, 7.0, 3.0);
        when(nutritionLogRepository.findAll()).thenReturn(List.of(
                dayLog("pasta", 100.0, LocalDate.of(2026, 8, 15).atTime(12, 0), 10L),
                dayLog("pasta", 100.0, LocalDate.of(2026, 8, 16).atTime(12, 0), 10L)));
        when(nutritionInfoRepository.findById("pasta")).thenReturn(Optional.of(pastaInfo));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(recipe));

        List<DailyNutritionSummary> result =
                service.getDailyBreakdown(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 16));

        assertThat(result.get(0).entries().get(0).recipeName()).isEqualTo("Tomato Pasta");
        assertThat(result.get(1).entries().get(0).recipeName()).isEqualTo("Tomato Pasta");
        verify(recipeRepository, times(1)).findById(10L);
    }

    @Test
    void getDailyBreakdown_recipeIdWithNoRecipeRow_leavesRecipeNameNullButKeepsRecipeId() {
        LocalDate d = LocalDate.of(2026, 8, 15);
        when(nutritionLogRepository.findAll())
                .thenReturn(List.of(dayLog("pasta", 100.0, d.atTime(12, 0), 99L)));
        when(nutritionInfoRepository.findById("pasta"))
                .thenReturn(Optional.of(info("pasta", 100.0, 200.0, 7.0, 3.0)));
        when(recipeRepository.findById(99L)).thenReturn(Optional.empty());

        DailyNutritionSummary day = service.getDailyBreakdown(d, d).get(0);

        assertThat(day.entries().get(0).recipeId()).isEqualTo(99L);
        assertThat(day.entries().get(0).recipeName()).isNull();
    }

    @Test
    void getDailyBreakdown_endBeforeStart_returnsEmptyList() {
        when(nutritionLogRepository.findAll()).thenReturn(List.of());

        assertThat(service.getDailyBreakdown(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1)))
                .isEmpty();
    }

    @Test
    void getDailyBreakdown_includesRecipeSourcedRowsThatGetSummaryWouldFilterOut() {
        Recipe recipe = new Recipe();
        recipe.setId(10L);
        recipe.setName("Tomato Pasta");

        LocalDate d = LocalDate.of(2026, 8, 15);
        // user_id null, recipeId set — the shape NutritionService.logRecipe writes.
        when(nutritionLogRepository.findAll())
                .thenReturn(List.of(dayLog("pasta", 400.0, d.atTime(12, 0), 10L)));
        when(nutritionInfoRepository.findById("pasta"))
                .thenReturn(Optional.of(info("pasta", 100.0, 200.0, 7.0, 3.0)));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(recipe));

        DailyNutritionSummary day = service.getDailyBreakdown(d, d).get(0);

        assertThat(day.entries()).hasSize(1);
        assertThat(day.totalCalories()).isEqualTo(200 * 4.0);
    }
}
