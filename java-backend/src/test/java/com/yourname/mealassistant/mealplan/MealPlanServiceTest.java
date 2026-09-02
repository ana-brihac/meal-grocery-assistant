package com.yourname.mealassistant.mealplan;

import com.yourname.mealassistant.common.exception.BadRequestException;
import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.grocerylist.GroceryListItem;
import com.yourname.mealassistant.grocerylist.GroceryListRepository;
import com.yourname.mealassistant.mealplan.dto.MealPlanRequest;
import com.yourname.mealassistant.mealplan.dto.MealPlanResponse;
import com.yourname.mealassistant.mealplan.dto.SelectPlanRequest;
import com.yourname.mealassistant.mealplan.dto.SlotReplacementRequest;
import com.yourname.mealassistant.mealplan.optimizer.MealPlanOptimizer;
import com.yourname.mealassistant.nutrition.NutritionService;
import com.yourname.mealassistant.nutrition.dto.RecipeNutrition;
import com.yourname.mealassistant.pricing.IngredientPriceService;
import com.yourname.mealassistant.preference.UserPreference;
import com.yourname.mealassistant.preference.UserPreferenceService;
import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredient;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import com.yourname.mealassistant.recipe.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// JUnit 5 + Mockito + AssertJ, no Spring context. MealPlanOptimizerTest already covers the
// constraint algorithm and IngredientPriceServiceTest the pricing rules; here the optimizer is
// mocked and we test the SERVICE around it: candidate assembly, target snapshot, the layout
// handed to the optimizer, the persistence round-trips, single-slot replacement, and
// clone-on-select.
@ExtendWith(MockitoExtension.class)
class MealPlanServiceTest {

    @Mock RecipeRepository recipeRepository;
    @Mock RecipeIngredientRepository recipeIngredientRepository;
    @Mock IngredientPriceService ingredientPriceService;
    @Mock NutritionService nutritionService;
    @Mock UserPreferenceService userPreferenceService;
    @Mock MealPlanOptimizer mealPlanOptimizer;
    @Mock MealPlanRepository mealPlanRepository;
    @Mock MealPlanSlotRepository mealPlanSlotRepository;
    @Mock GroceryListRepository groceryListRepository;

    @InjectMocks MealPlanService service;

    private static final LocalDate WEEK = LocalDate.of(2026, 9, 1);

    // ---- helpers ----

    private static Recipe recipe(long id, String name) {
        Recipe r = new Recipe();
        r.setId(id);
        r.setName(name);
        return r;
    }

    private static UserPreference prefs(Double cal, Double protein, Double fiber, String budget, Integer batch) {
        UserPreference p = new UserPreference(cal, protein, fiber, budget == null ? null : new BigDecimal(budget));
        p.setMealPrepBatchSize(batch);
        return p;
    }

    private static RecipeIngredient ing(String name, double qty) {
        RecipeIngredient ri = new RecipeIngredient();
        ri.setIngredientName(name);
        ri.setQuantity(qty);
        return ri;
    }

    private static MealPlanOptimizer.RecipeCandidate cand(Recipe r, double cal, double protein, double fiber,
                                                          String cost, boolean costComplete, boolean nutritionComplete) {
        return new MealPlanOptimizer.RecipeCandidate(r, cal, protein, fiber,
                cost == null ? null : new BigDecimal(cost), costComplete, nutritionComplete);
    }

    private static MealPlan planRow(Long id, String status, LocalDate week) {
        MealPlan p = new MealPlan();
        p.setId(id);
        p.setStatus(status);
        p.setWeekStartDate(week);
        return p;
    }

    private static MealPlan planWithSnapshots(Long id, LocalDate week, Double cal, Double protein,
                                              Double fiber, String budget) {
        MealPlan p = planRow(id, MealPlan.STATUS_DRAFT, week);
        p.setCalorieTargetSnapshot(cal);
        p.setProteinTargetSnapshot(protein);
        p.setFiberTargetSnapshot(fiber);
        p.setWeeklyBudgetSnapshot(budget == null ? null : new BigDecimal(budget));
        return p;
    }

    private static MealPlanSlot slotRow(Long id, Long mealPlanId, LocalDate date, String mealType,
                                        Long recipeId, Double calories, String cost) {
        MealPlanSlot s = new MealPlanSlot();
        s.setId(id);
        s.setMealPlanId(mealPlanId);
        s.setSlotDate(date);
        s.setMealType(mealType);
        s.setRecipeId(recipeId);
        s.setServings(1.0);
        s.setEstimatedCalories(calories);
        s.setEstimatedProtein(20.0);
        s.setEstimatedFiber(5.0);
        s.setEstimatedCost(cost == null ? null : new BigDecimal(cost));
        s.setCostComplete(cost != null);
        s.setNutritionComplete(true);
        return s;
    }

    private void stubPlanPersistence(long planId) {
        when(mealPlanRepository.save(any(MealPlan.class))).thenAnswer(i -> {
            MealPlan p = i.getArgument(0);
            if (p.getId() == null) p.setId(planId);
            return p;
        });
        when(mealPlanSlotRepository.saveAll(anyList())).thenAnswer(i -> {
            List<MealPlanSlot> slots = i.getArgument(0);
            long id = 1;
            for (MealPlanSlot s : slots) {
                if (s.getId() == null) s.setId(id++);
            }
            return slots;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    // ================= generatePlan: validation =================

    @Test
    void generatePlan_nullRequest_throwsBadRequest() {
        assertThatThrownBy(() -> service.generatePlan(null)).isInstanceOf(BadRequestException.class);
        verify(mealPlanRepository, never()).save(any());
    }

    @Test
    void generatePlan_nullWeekStartDate_throwsBadRequest() {
        assertThatThrownBy(() -> service.generatePlan(new MealPlanRequest(null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    // ================= generatePlan: target snapshot =================

    @Test
    void generatePlan_snapshotsCurrentPreferenceTargetsAndBudgetOntoThePlanRow() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2100.0, 110.0, 28.0, "80", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        ArgumentCaptor<MealPlan> plan = ArgumentCaptor.forClass(MealPlan.class);
        verify(mealPlanRepository).save(plan.capture());
        assertThat(plan.getValue().getStatus()).isEqualTo(MealPlan.STATUS_DRAFT);
        assertThat(plan.getValue().getWeekStartDate()).isEqualTo(WEEK);
        assertThat(plan.getValue().getCalorieTargetSnapshot()).isEqualTo(2100.0);
        assertThat(plan.getValue().getProteinTargetSnapshot()).isEqualTo(110.0);
        assertThat(plan.getValue().getFiberTargetSnapshot()).isEqualTo(28.0);
        assertThat(plan.getValue().getWeeklyBudgetSnapshot()).isEqualByComparingTo("80");
    }

    @Test
    void generatePlan_passesTheSnapshotTargetsToTheOptimizerAsPlanConstraints() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2100.0, 110.0, 28.0, "80", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        ArgumentCaptor<MealPlanOptimizer.PlanConstraints> c =
                ArgumentCaptor.forClass(MealPlanOptimizer.PlanConstraints.class);
        verify(mealPlanOptimizer).selectPlan(anyList(), c.capture(), any());
        assertThat(c.getValue().dailyCalorieTarget()).isEqualTo(2100.0);
        assertThat(c.getValue().dailyProteinTarget()).isEqualTo(110.0);
        assertThat(c.getValue().dailyFiberTarget()).isEqualTo(28.0);
        assertThat(c.getValue().weeklyBudget()).isEqualByComparingTo("80");
    }

    // ================= generatePlan: candidate assembly =================

    @Test
    void generatePlan_buildsOneRecipeCandidatePerRecipe_withComputedNutritionAndCost() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(1, "R1"), recipe(2, "R2")));
        when(nutritionService.computeRecipeNutrition(1L, 1.0)).thenReturn(new RecipeNutrition(500, 25, 8, true));
        when(nutritionService.computeRecipeNutrition(2L, 1.0)).thenReturn(new RecipeNutrition(600, 30, 9, true));
        when(recipeIngredientRepository.findByRecipeId(1L)).thenReturn(List.of(ing("flour", 100.0)));
        when(recipeIngredientRepository.findByRecipeId(2L)).thenReturn(List.of(ing("rice", 200.0)));
        when(ingredientPriceService.estimateIngredientCost("flour", 100.0)).thenReturn(Optional.of(new BigDecimal("1.50")));
        when(ingredientPriceService.estimateIngredientCost("rice", 200.0)).thenReturn(Optional.of(new BigDecimal("2.00")));
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        ArgumentCaptor<List<MealPlanOptimizer.RecipeCandidate>> cands = listCaptor();
        verify(mealPlanOptimizer).selectPlan(cands.capture(), any(), any());
        assertThat(cands.getValue()).hasSize(2);
        assertThat(cands.getValue()).extracting(c -> c.recipe().getId()).containsExactly(1L, 2L);
        MealPlanOptimizer.RecipeCandidate c1 = cands.getValue().get(0);
        assertThat(c1.estimatedCalories()).isEqualTo(500.0);
        assertThat(c1.estimatedProtein()).isEqualTo(25.0);
        assertThat(c1.estimatedFiber()).isEqualTo(8.0);
        assertThat(c1.estimatedCost()).isEqualByComparingTo("1.50");
        assertThat(c1.costComplete()).isTrue();
        assertThat(c1.nutritionComplete()).isTrue();
    }

    @Test
    void generatePlan_recipeWithAnUnpricedIngredient_yieldsCostIncompleteCandidate() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(1, "R1")));
        when(nutritionService.computeRecipeNutrition(1L, 1.0)).thenReturn(new RecipeNutrition(500, 25, 8, true));
        when(recipeIngredientRepository.findByRecipeId(1L)).thenReturn(List.of(ing("a", 100.0), ing("b", 50.0)));
        when(ingredientPriceService.estimateIngredientCost("a", 100.0)).thenReturn(Optional.of(new BigDecimal("2.00")));
        when(ingredientPriceService.estimateIngredientCost("b", 50.0)).thenReturn(Optional.empty());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        ArgumentCaptor<List<MealPlanOptimizer.RecipeCandidate>> cands = listCaptor();
        verify(mealPlanOptimizer).selectPlan(cands.capture(), any(), any());
        MealPlanOptimizer.RecipeCandidate c = cands.getValue().get(0);
        assertThat(c.costComplete()).isFalse();
        assertThat(c.estimatedCost()).isEqualByComparingTo("2.00"); // only the priced ingredient
    }

    @Test
    void generatePlan_noRecipes_stillPersistsThePlanRow_withNoSlots() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of("2026-09-01: below the calorie band")));
        stubPlanPersistence(1L);

        MealPlanResponse res = service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        verify(mealPlanRepository).save(any(MealPlan.class));
        ArgumentCaptor<List<MealPlanSlot>> slots = listCaptor();
        verify(mealPlanSlotRepository).saveAll(slots.capture());
        assertThat(slots.getValue()).isEmpty();
        assertThat(res.slots()).isEmpty();
        assertThat(res.warnings()).containsExactly("2026-09-01: below the calorie band");
    }

    // ================= generatePlan: layout =================

    @Test
    void generatePlan_defaultLayout_isSevenConsecutiveDaysAndBreakfastLunchDinner() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        ArgumentCaptor<MealPlanOptimizer.PlanLayout> layout =
                ArgumentCaptor.forClass(MealPlanOptimizer.PlanLayout.class);
        verify(mealPlanOptimizer).selectPlan(anyList(), any(), layout.capture());
        assertThat(layout.getValue().dates()).containsExactly(
                WEEK, WEEK.plusDays(1), WEEK.plusDays(2), WEEK.plusDays(3),
                WEEK.plusDays(4), WEEK.plusDays(5), WEEK.plusDays(6));
        assertThat(layout.getValue().mealTypes()).containsExactly("BREAKFAST", "LUNCH", "DINNER");
        assertThat(layout.getValue().servingsPerMeal()).isEqualTo(1.0);
    }

    @Test
    void generatePlan_requestOverrides_daysMealTypesAndServingsArePassedThrough() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(1, "R1")));
        when(nutritionService.computeRecipeNutrition(1L, 2.0)).thenReturn(new RecipeNutrition(500, 25, 8, true));
        when(recipeIngredientRepository.findByRecipeId(1L)).thenReturn(List.of(ing("flour", 100.0)));
        when(ingredientPriceService.estimateIngredientCost("flour", 200.0)).thenReturn(Optional.of(new BigDecimal("1.00")));
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, 2, List.of("BRUNCH", "SUPPER"), 2.0));

        ArgumentCaptor<MealPlanOptimizer.PlanLayout> layout =
                ArgumentCaptor.forClass(MealPlanOptimizer.PlanLayout.class);
        verify(mealPlanOptimizer).selectPlan(anyList(), any(), layout.capture());
        assertThat(layout.getValue().dates()).containsExactly(WEEK, WEEK.plusDays(1));
        assertThat(layout.getValue().mealTypes()).containsExactly("BRUNCH", "SUPPER");
        assertThat(layout.getValue().servingsPerMeal()).isEqualTo(2.0);
        verify(nutritionService).computeRecipeNutrition(1L, 2.0);
        verify(ingredientPriceService).estimateIngredientCost("flour", 200.0); // 100 g * 2 servings
    }

    @Test
    void generatePlan_nonPositiveOrNullRequestFields_fallBackToDefaults() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        service.generatePlan(new MealPlanRequest(WEEK, 0, List.of(), -1.0));

        ArgumentCaptor<MealPlanOptimizer.PlanLayout> layout =
                ArgumentCaptor.forClass(MealPlanOptimizer.PlanLayout.class);
        verify(mealPlanOptimizer).selectPlan(anyList(), any(), layout.capture());
        assertThat(layout.getValue().dates()).hasSize(7);
        assertThat(layout.getValue().mealTypes()).containsExactly("BREAKFAST", "LUNCH", "DINNER");
        assertThat(layout.getValue().servingsPerMeal()).isEqualTo(1.0);
    }

    @Test
    void generatePlan_mealPrepBatchSize_takenFromPreferencesAndClampedToAtLeastOne() {
        when(userPreferenceService.getPreferences()).thenReturn(
                prefs(2000.0, 100.0, 30.0, "50", 3),
                prefs(2000.0, 100.0, 30.0, "50", null),
                prefs(2000.0, 100.0, 30.0, "50", 0));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of()));
        stubPlanPersistence(1L);

        MealPlanRequest req = new MealPlanRequest(WEEK, null, null, null);
        service.generatePlan(req);
        service.generatePlan(req);
        service.generatePlan(req);

        ArgumentCaptor<MealPlanOptimizer.PlanLayout> layout =
                ArgumentCaptor.forClass(MealPlanOptimizer.PlanLayout.class);
        verify(mealPlanOptimizer, times(3)).selectPlan(anyList(), any(), layout.capture());
        assertThat(layout.getAllValues()).extracting(l -> l.maxBatchSize()).containsExactly(3, 1, 1);
    }

    // ================= generatePlan: slot persistence + response =================

    @Test
    void generatePlan_persistsOneSlotPerOptimizerAssignment_withContributionSnapshot() {
        Recipe r1 = recipe(1, "R1");
        Recipe r2 = recipe(2, "R2");
        List<MealPlanOptimizer.SlotAssignment> assigns = List.of(
                new MealPlanOptimizer.SlotAssignment(WEEK, "BREAKFAST", cand(r1, 500, 25, 8, "2.50", true, true)),
                new MealPlanOptimizer.SlotAssignment(WEEK, "LUNCH", cand(r2, 600, 30, 9, "3.00", true, true)));
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(assigns, List.of()));
        stubPlanPersistence(42L);
        when(recipeRepository.findById(1L)).thenReturn(Optional.of(r1));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(r2));

        service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        ArgumentCaptor<List<MealPlanSlot>> slots = listCaptor();
        verify(mealPlanSlotRepository).saveAll(slots.capture());
        assertThat(slots.getValue()).hasSize(2);
        MealPlanSlot s0 = slots.getValue().get(0);
        assertThat(s0.getMealPlanId()).isEqualTo(42L);
        assertThat(s0.getSlotDate()).isEqualTo(WEEK);
        assertThat(s0.getMealType()).isEqualTo("BREAKFAST");
        assertThat(s0.getRecipeId()).isEqualTo(1L);
        assertThat(s0.getServings()).isEqualTo(1.0);
        assertThat(s0.getEstimatedCalories()).isEqualTo(500.0);
        assertThat(s0.getEstimatedProtein()).isEqualTo(25.0);
        assertThat(s0.getEstimatedFiber()).isEqualTo(8.0);
        assertThat(s0.getEstimatedCost()).isEqualByComparingTo("2.50");
        assertThat(s0.getCostComplete()).isTrue();
        assertThat(s0.getNutritionComplete()).isTrue();
    }

    @Test
    void generatePlan_response_totalCostSumsOnlyCostCompleteSlots_andSetsIncompleteFlags() {
        Recipe r1 = recipe(1, "R1");
        Recipe r2 = recipe(2, "R2");
        List<MealPlanOptimizer.SlotAssignment> assigns = List.of(
                new MealPlanOptimizer.SlotAssignment(WEEK, "BREAKFAST", cand(r1, 500, 25, 8, "3.00", true, true)),
                new MealPlanOptimizer.SlotAssignment(WEEK, "LUNCH", cand(r2, 600, 30, 9, "1.00", false, false)));
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(assigns, List.of()));
        stubPlanPersistence(1L);
        when(recipeRepository.findById(1L)).thenReturn(Optional.of(r1));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(r2));

        MealPlanResponse res = service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        assertThat(res.totalEstimatedCost()).isEqualByComparingTo("3.00"); // the incomplete slot excluded
        assertThat(res.costIncomplete()).isTrue();
        assertThat(res.nutritionIncomplete()).isTrue();
    }

    @Test
    void generatePlan_response_slotsSortedByDateThenId_andRecipeNameResolvedForEachSlot() {
        Recipe r1 = recipe(1, "R1");
        Recipe r2 = recipe(2, "R2");
        // assignment order deliberately later-day first
        List<MealPlanOptimizer.SlotAssignment> assigns = List.of(
                new MealPlanOptimizer.SlotAssignment(WEEK.plusDays(1), "BREAKFAST", cand(r1, 500, 25, 8, "2.00", true, true)),
                new MealPlanOptimizer.SlotAssignment(WEEK, "BREAKFAST", cand(r2, 500, 25, 8, "2.00", true, true)));
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(assigns, List.of()));
        stubPlanPersistence(1L);
        when(recipeRepository.findById(1L)).thenReturn(Optional.of(r1));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(r2));

        MealPlanResponse res = service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        assertThat(res.slots()).extracting(MealPlanResponse.PlannedSlot::date)
                .containsExactly(WEEK, WEEK.plusDays(1));
        assertThat(res.slots()).allSatisfy(s -> assertThat(s.recipeName()).isNotNull());
    }

    @Test
    void generatePlan_response_carriesOptimizerWarningsDeduplicated() {
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(recipeRepository.findAll()).thenReturn(List.of());
        when(mealPlanOptimizer.selectPlan(anyList(), any(), any()))
                .thenReturn(new MealPlanOptimizer.PlanResult(List.of(), List.of("dup", "dup", "unique")));
        stubPlanPersistence(1L);

        MealPlanResponse res = service.generatePlan(new MealPlanRequest(WEEK, null, null, null));

        assertThat(res.warnings()).containsExactly("dup", "unique");
    }

    // ================= getHistory / getPlan =================

    @Test
    void getHistory_mapsEachPlanWithItsOwnSlots_andEmptyWarnings() {
        MealPlan a = planRow(1L, MealPlan.STATUS_DRAFT, WEEK);
        MealPlan b = planRow(2L, MealPlan.STATUS_SELECTED, WEEK.plusWeeks(1));
        when(mealPlanRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a, b));
        when(mealPlanSlotRepository.findByMealPlanId(1L))
                .thenReturn(List.of(slotRow(10L, 1L, WEEK, "BREAKFAST", 100L, 500.0, "2.00")));
        when(mealPlanSlotRepository.findByMealPlanId(2L))
                .thenReturn(List.of(slotRow(20L, 2L, WEEK.plusWeeks(1), "LUNCH", 200L, 600.0, "3.00")));
        when(recipeRepository.findById(100L)).thenReturn(Optional.of(recipe(100, "RA")));
        when(recipeRepository.findById(200L)).thenReturn(Optional.of(recipe(200, "RB")));

        List<MealPlanResponse> res = service.getHistory();

        assertThat(res).extracting(MealPlanResponse::planId).containsExactly(1L, 2L);
        assertThat(res.get(0).slots()).hasSize(1);
        assertThat(res.get(0).warnings()).isEmpty();
        assertThat(res.get(1).slots()).hasSize(1);
        assertThat(res.get(1).warnings()).isEmpty();
    }

    @Test
    void getPlan_unknownId_throwsNotFound() {
        when(mealPlanRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPlan(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getPlan_knownId_returnsPlanWithItsSlotsAndEmptyWarnings() {
        when(mealPlanRepository.findById(5L)).thenReturn(Optional.of(planRow(5L, MealPlan.STATUS_DRAFT, WEEK)));
        when(mealPlanSlotRepository.findByMealPlanId(5L))
                .thenReturn(List.of(slotRow(10L, 5L, WEEK, "BREAKFAST", 100L, 500.0, "2.00")));
        when(recipeRepository.findById(100L)).thenReturn(Optional.of(recipe(100, "RA")));

        MealPlanResponse res = service.getPlan(5L);

        assertThat(res.planId()).isEqualTo(5L);
        assertThat(res.slots()).hasSize(1);
        assertThat(res.warnings()).isEmpty();
    }

    // ================= replaceSlot =================

    @Test
    void replaceSlot_unknownPlan_throwsNotFound() {
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceSlot(1L, 2L, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void replaceSlot_slotIdNotInThatPlan_throwsNotFound() {
        when(mealPlanRepository.findById(1L))
                .thenReturn(Optional.of(planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50")));
        when(mealPlanSlotRepository.findByMealPlanId(1L))
                .thenReturn(List.of(slotRow(10L, 1L, WEEK, "LUNCH", 1L, 600.0, "5.00")));

        assertThatThrownBy(() -> service.replaceSlot(1L, 999L, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void replaceSlot_swapsTheTargetSlot_persistingTheChosenCandidatesContribution() {
        MealPlan plan = planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50");
        MealPlanSlot target = slotRow(10L, 1L, LocalDate.of(2026, 9, 4), "LUNCH", 1L, 600.0, "5.00");
        MealPlanSlot other = slotRow(11L, 1L, LocalDate.of(2026, 9, 4), "DINNER", 2L, 700.0, "6.00");
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(mealPlanSlotRepository.findByMealPlanId(1L)).thenReturn(List.of(target, other));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(3, "R3")));
        when(nutritionService.computeRecipeNutrition(3L, 1.0)).thenReturn(new RecipeNutrition(550, 30, 8, true));
        when(recipeIngredientRepository.findByRecipeId(3L)).thenReturn(List.of(ing("beef", 150.0)));
        when(ingredientPriceService.estimateIngredientCost("beef", 150.0)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(mealPlanOptimizer.selectReplacement(anyList(), any())).thenReturn(
                new MealPlanOptimizer.SlotResult(cand(recipe(3, "R3"), 550, 30, 8, "4.00", true, true), List.of()));
        when(groceryListRepository.findByMealPlanId(1L)).thenReturn(List.of());
        when(recipeRepository.findById(3L)).thenReturn(Optional.of(recipe(3, "R3")));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(recipe(2, "R2")));

        service.replaceSlot(1L, 10L, null);

        ArgumentCaptor<MealPlanSlot> saved = ArgumentCaptor.forClass(MealPlanSlot.class);
        verify(mealPlanSlotRepository).save(saved.capture());
        assertThat(saved.getValue().getRecipeId()).isEqualTo(3L);
        assertThat(saved.getValue().getEstimatedCalories()).isEqualTo(550.0);
        assertThat(saved.getValue().getEstimatedProtein()).isEqualTo(30.0);
        assertThat(saved.getValue().getEstimatedFiber()).isEqualTo(8.0);
        assertThat(saved.getValue().getEstimatedCost()).isEqualByComparingTo("4.00");
        assertThat(saved.getValue().getCostComplete()).isTrue();
        assertThat(saved.getValue().getNutritionComplete()).isTrue();
    }

    @Test
    void replaceSlot_excludesEveryRecipeAlreadyInThePlan_fromTheCandidatePool() {
        MealPlan plan = planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50");
        MealPlanSlot target = slotRow(10L, 1L, LocalDate.of(2026, 9, 4), "LUNCH", 1L, 600.0, "5.00");
        MealPlanSlot other = slotRow(11L, 1L, LocalDate.of(2026, 9, 4), "DINNER", 2L, 700.0, "6.00");
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(mealPlanSlotRepository.findByMealPlanId(1L)).thenReturn(List.of(target, other));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(1, "R1"), recipe(2, "R2"), recipe(3, "R3")));
        when(nutritionService.computeRecipeNutrition(3L, 1.0)).thenReturn(new RecipeNutrition(550, 30, 8, true));
        when(recipeIngredientRepository.findByRecipeId(3L)).thenReturn(List.of(ing("beef", 150.0)));
        when(ingredientPriceService.estimateIngredientCost("beef", 150.0)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(mealPlanOptimizer.selectReplacement(anyList(), any())).thenReturn(
                new MealPlanOptimizer.SlotResult(cand(recipe(3, "R3"), 550, 30, 8, "4.00", true, true), List.of()));
        when(groceryListRepository.findByMealPlanId(1L)).thenReturn(List.of());
        when(recipeRepository.findById(3L)).thenReturn(Optional.of(recipe(3, "R3")));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(recipe(2, "R2")));

        service.replaceSlot(1L, 10L, null);

        ArgumentCaptor<List<MealPlanOptimizer.RecipeCandidate>> cands = listCaptor();
        ArgumentCaptor<MealPlanOptimizer.SlotConstraints> sc =
                ArgumentCaptor.forClass(MealPlanOptimizer.SlotConstraints.class);
        verify(mealPlanOptimizer).selectReplacement(cands.capture(), sc.capture());
        assertThat(cands.getValue()).extracting(c -> c.recipe().getId()).containsExactly(3L);
        assertThat(sc.getValue().excludeRecipeIds()).contains(1L, 2L);
    }

    @Test
    void replaceSlot_noCandidateRecipesLeft_returnsAWarning_andNeverCallsTheOptimizer() {
        MealPlan plan = planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50");
        MealPlanSlot target = slotRow(10L, 1L, LocalDate.of(2026, 9, 4), "LUNCH", 1L, 600.0, "5.00");
        MealPlanSlot other = slotRow(11L, 1L, LocalDate.of(2026, 9, 4), "DINNER", 2L, 700.0, "6.00");
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(mealPlanSlotRepository.findByMealPlanId(1L)).thenReturn(List.of(target, other));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(1, "R1"), recipe(2, "R2")));
        when(groceryListRepository.findByMealPlanId(1L)).thenReturn(List.of());
        when(recipeRepository.findById(1L)).thenReturn(Optional.of(recipe(1, "R1")));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(recipe(2, "R2")));

        MealPlanResponse res = service.replaceSlot(1L, 10L, null);

        assertThat(res.warnings())
                .contains("No alternative recipe available for the LUNCH slot on 2026-09-04.");
        verify(mealPlanOptimizer, never()).selectReplacement(anyList(), any());
        verify(mealPlanSlotRepository, never()).save(any(MealPlanSlot.class));
    }

    @Test
    void replaceSlot_existingGroceryListForThePlan_isMarkedStaleAndResaved() {
        MealPlan plan = planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50");
        MealPlanSlot target = slotRow(10L, 1L, LocalDate.of(2026, 9, 4), "LUNCH", 1L, 600.0, "5.00");
        MealPlanSlot other = slotRow(11L, 1L, LocalDate.of(2026, 9, 4), "DINNER", 2L, 700.0, "6.00");
        GroceryListItem g1 = new GroceryListItem();
        GroceryListItem g2 = new GroceryListItem();
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(mealPlanSlotRepository.findByMealPlanId(1L)).thenReturn(List.of(target, other));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(3, "R3")));
        when(nutritionService.computeRecipeNutrition(3L, 1.0)).thenReturn(new RecipeNutrition(550, 30, 8, true));
        when(recipeIngredientRepository.findByRecipeId(3L)).thenReturn(List.of(ing("beef", 150.0)));
        when(ingredientPriceService.estimateIngredientCost("beef", 150.0)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(mealPlanOptimizer.selectReplacement(anyList(), any())).thenReturn(
                new MealPlanOptimizer.SlotResult(cand(recipe(3, "R3"), 550, 30, 8, "4.00", true, true), List.of()));
        when(groceryListRepository.findByMealPlanId(1L)).thenReturn(List.of(g1, g2));
        when(recipeRepository.findById(3L)).thenReturn(Optional.of(recipe(3, "R3")));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(recipe(2, "R2")));

        service.replaceSlot(1L, 10L, null);

        assertThat(g1.getStale()).isTrue();
        assertThat(g2.getStale()).isTrue();
        verify(groceryListRepository).saveAll(List.of(g1, g2));
    }

    @Test
    void replaceSlot_noGroceryListForThePlan_doesNotTouchTheGroceryRepository() {
        MealPlan plan = planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50");
        MealPlanSlot target = slotRow(10L, 1L, LocalDate.of(2026, 9, 4), "LUNCH", 1L, 600.0, "5.00");
        MealPlanSlot other = slotRow(11L, 1L, LocalDate.of(2026, 9, 4), "DINNER", 2L, 700.0, "6.00");
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(mealPlanSlotRepository.findByMealPlanId(1L)).thenReturn(List.of(target, other));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(3, "R3")));
        when(nutritionService.computeRecipeNutrition(3L, 1.0)).thenReturn(new RecipeNutrition(550, 30, 8, true));
        when(recipeIngredientRepository.findByRecipeId(3L)).thenReturn(List.of(ing("beef", 150.0)));
        when(ingredientPriceService.estimateIngredientCost("beef", 150.0)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(mealPlanOptimizer.selectReplacement(anyList(), any())).thenReturn(
                new MealPlanOptimizer.SlotResult(cand(recipe(3, "R3"), 550, 30, 8, "4.00", true, true), List.of()));
        when(groceryListRepository.findByMealPlanId(1L)).thenReturn(List.of());
        when(recipeRepository.findById(3L)).thenReturn(Optional.of(recipe(3, "R3")));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(recipe(2, "R2")));

        service.replaceSlot(1L, 10L, null);

        verify(groceryListRepository, never()).saveAll(anyList());
    }

    @Test
    void replaceSlot_bodyExcludeRecipeIds_areUnionedIntoTheExclusionSet() {
        MealPlan plan = planWithSnapshots(1L, WEEK, 2000.0, 100.0, 30.0, "50");
        MealPlanSlot target = slotRow(10L, 1L, LocalDate.of(2026, 9, 4), "LUNCH", 1L, 600.0, "5.00");
        MealPlanSlot other = slotRow(11L, 1L, LocalDate.of(2026, 9, 4), "DINNER", 2L, 700.0, "6.00");
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(mealPlanSlotRepository.findByMealPlanId(1L)).thenReturn(List.of(target, other));
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(3, "R3"), recipe(7, "R7")));
        when(nutritionService.computeRecipeNutrition(3L, 1.0)).thenReturn(new RecipeNutrition(550, 30, 8, true));
        when(recipeIngredientRepository.findByRecipeId(3L)).thenReturn(List.of(ing("beef", 150.0)));
        when(ingredientPriceService.estimateIngredientCost("beef", 150.0)).thenReturn(Optional.of(new BigDecimal("4.00")));
        when(mealPlanOptimizer.selectReplacement(anyList(), any())).thenReturn(
                new MealPlanOptimizer.SlotResult(cand(recipe(3, "R3"), 550, 30, 8, "4.00", true, true), List.of()));
        when(groceryListRepository.findByMealPlanId(1L)).thenReturn(List.of());
        when(recipeRepository.findById(3L)).thenReturn(Optional.of(recipe(3, "R3")));
        when(recipeRepository.findById(2L)).thenReturn(Optional.of(recipe(2, "R2")));

        service.replaceSlot(1L, 10L, new SlotReplacementRequest(List.of(7L)));

        ArgumentCaptor<List<MealPlanOptimizer.RecipeCandidate>> cands = listCaptor();
        ArgumentCaptor<MealPlanOptimizer.SlotConstraints> sc =
                ArgumentCaptor.forClass(MealPlanOptimizer.SlotConstraints.class);
        verify(mealPlanOptimizer).selectReplacement(cands.capture(), sc.capture());
        assertThat(cands.getValue()).extracting(c -> c.recipe().getId()).containsExactly(3L);
        assertThat(sc.getValue().excludeRecipeIds()).contains(7L);
    }

    // ================= selectForWeek =================

    @Test
    void selectForWeek_nullRequestOrWeekStartDate_throwsBadRequest() {
        assertThatThrownBy(() -> service.selectForWeek(1L, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.selectForWeek(1L, new SelectPlanRequest(null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void selectForWeek_unknownSourcePlan_throwsNotFound() {
        when(mealPlanRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectForWeek(1L, new SelectPlanRequest(WEEK.plusWeeks(1))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void selectForWeek_insertsAClonePlan_statusSelected_sourcePlanIdSet_targetsReSnapshot() {
        MealPlan source = planWithSnapshots(3L, WEEK, 1000.0, 50.0, 10.0, "40");
        source.setStatus(MealPlan.STATUS_SELECTED);
        when(mealPlanRepository.findById(3L)).thenReturn(Optional.of(source));
        when(mealPlanSlotRepository.findByMealPlanId(3L)).thenReturn(List.of());
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2100.0, 110.0, 28.0, "80", 1));
        when(mealPlanRepository.findByWeekStartDateAndStatus(WEEK.plusWeeks(1), MealPlan.STATUS_SELECTED))
                .thenReturn(List.of());
        stubPlanPersistence(99L);
        when(mealPlanSlotRepository.findByMealPlanId(99L)).thenReturn(List.of());

        service.selectForWeek(3L, new SelectPlanRequest(WEEK.plusWeeks(1)));

        ArgumentCaptor<MealPlan> clone = ArgumentCaptor.forClass(MealPlan.class);
        verify(mealPlanRepository).save(clone.capture());
        assertThat(clone.getValue().getStatus()).isEqualTo(MealPlan.STATUS_SELECTED);
        assertThat(clone.getValue().getSourcePlanId()).isEqualTo(3L);
        assertThat(clone.getValue().getWeekStartDate()).isEqualTo(WEEK.plusWeeks(1));
        assertThat(clone.getValue().getCalorieTargetSnapshot()).isEqualTo(2100.0);
        assertThat(clone.getValue().getProteinTargetSnapshot()).isEqualTo(110.0);
        assertThat(clone.getValue().getFiberTargetSnapshot()).isEqualTo(28.0);
        assertThat(clone.getValue().getWeeklyBudgetSnapshot()).isEqualByComparingTo("80");
    }

    @Test
    void selectForWeek_copiesEverySourceSlot_shiftedByTheWeekOffset_withContributionSnapshot() {
        MealPlan source = planRow(3L, MealPlan.STATUS_SELECTED, WEEK); // null target snapshots -> no reoptimise
        MealPlanSlot src = slotRow(10L, 3L, LocalDate.of(2026, 9, 2), "LUNCH", 5L, 500.0, "3.00");
        when(mealPlanRepository.findById(3L)).thenReturn(Optional.of(source));
        when(mealPlanSlotRepository.findByMealPlanId(3L)).thenReturn(List.of(src));
        when(userPreferenceService.getPreferences()).thenReturn(prefs(null, null, null, null, 1));
        when(mealPlanRepository.findByWeekStartDateAndStatus(any(), eq(MealPlan.STATUS_SELECTED)))
                .thenReturn(List.of());
        stubPlanPersistence(99L);
        when(mealPlanSlotRepository.findByMealPlanId(99L)).thenReturn(List.of());

        service.selectForWeek(3L, new SelectPlanRequest(WEEK.plusWeeks(1)));

        ArgumentCaptor<List<MealPlanSlot>> saved = listCaptor();
        verify(mealPlanSlotRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        MealPlanSlot c = saved.getValue().get(0);
        assertThat(c.getMealPlanId()).isEqualTo(99L);
        assertThat(c.getSlotDate()).isEqualTo(LocalDate.of(2026, 9, 9)); // 2026-09-02 + 7
        assertThat(c.getMealType()).isEqualTo("LUNCH");
        assertThat(c.getRecipeId()).isEqualTo(5L);
        assertThat(c.getServings()).isEqualTo(1.0);
        assertThat(c.getEstimatedCalories()).isEqualTo(500.0);
        assertThat(c.getEstimatedCost()).isEqualByComparingTo("3.00");
        assertThat(c.getCostComplete()).isTrue();
        assertThat(c.getNutritionComplete()).isTrue();
    }

    @Test
    void selectForWeek_demotesAnyExistingSelectedPlanForThatWeek_toDraft() {
        MealPlan source = planRow(3L, MealPlan.STATUS_SELECTED, WEEK);
        MealPlan existingSelected = planRow(50L, MealPlan.STATUS_SELECTED, WEEK.plusWeeks(1));
        when(mealPlanRepository.findById(3L)).thenReturn(Optional.of(source));
        when(mealPlanSlotRepository.findByMealPlanId(3L)).thenReturn(List.of());
        when(userPreferenceService.getPreferences()).thenReturn(prefs(null, null, null, null, 1));
        when(mealPlanRepository.findByWeekStartDateAndStatus(WEEK.plusWeeks(1), MealPlan.STATUS_SELECTED))
                .thenReturn(List.of(existingSelected));
        stubPlanPersistence(99L);
        when(mealPlanSlotRepository.findByMealPlanId(99L)).thenReturn(List.of());

        service.selectForWeek(3L, new SelectPlanRequest(WEEK.plusWeeks(1)));

        assertThat(existingSelected.getStatus()).isEqualTo(MealPlan.STATUS_DRAFT);
        verify(mealPlanRepository).save(existingSelected);
    }

    @Test
    void selectForWeek_copiedDaysAlreadyWithinCurrentTargets_areNotReoptimised() {
        MealPlan source = planRow(3L, MealPlan.STATUS_SELECTED, WEEK);
        MealPlanSlot src = slotRow(10L, 3L, LocalDate.of(2026, 9, 2), "LUNCH", 5L, 500.0, "3.00");
        when(mealPlanRepository.findById(3L)).thenReturn(Optional.of(source));
        when(mealPlanSlotRepository.findByMealPlanId(3L)).thenReturn(List.of(src));
        when(userPreferenceService.getPreferences()).thenReturn(prefs(null, null, null, null, 1));
        when(mealPlanRepository.findByWeekStartDateAndStatus(any(), eq(MealPlan.STATUS_SELECTED)))
                .thenReturn(List.of());
        stubPlanPersistence(99L);
        when(mealPlanSlotRepository.findByMealPlanId(99L)).thenReturn(List.of());

        MealPlanResponse res = service.selectForWeek(3L, new SelectPlanRequest(WEEK.plusWeeks(1)));

        verify(mealPlanOptimizer, never()).selectReplacement(anyList(), any());
        assertThat(res.warnings()).isEmpty();
    }

    @Test
    void selectForWeek_copiedDayOutOfBand_triggersPerSlotReoptimise_andAccumulatesWarnings() {
        MealPlan source = planRow(3L, MealPlan.STATUS_SELECTED, WEEK);
        MealPlanSlot src = slotRow(10L, 3L, LocalDate.of(2026, 9, 2), "LUNCH", 5L, 100.0, "2.00"); // far below band
        when(mealPlanRepository.findById(3L)).thenReturn(Optional.of(source));
        when(mealPlanSlotRepository.findByMealPlanId(3L)).thenReturn(List.of(src));
        when(userPreferenceService.getPreferences()).thenReturn(prefs(2000.0, 100.0, 30.0, "50", 1));
        when(mealPlanRepository.findByWeekStartDateAndStatus(any(), eq(MealPlan.STATUS_SELECTED)))
                .thenReturn(List.of());
        stubPlanPersistence(99L);
        when(recipeRepository.findAll()).thenReturn(List.of(recipe(6, "R6")));
        when(nutritionService.computeRecipeNutrition(6L, 1.0)).thenReturn(new RecipeNutrition(600, 40, 12, true));
        when(recipeIngredientRepository.findByRecipeId(6L)).thenReturn(List.of(ing("x", 100.0)));
        when(ingredientPriceService.estimateIngredientCost("x", 100.0)).thenReturn(Optional.of(new BigDecimal("3.00")));
        when(mealPlanOptimizer.selectReplacement(anyList(), any())).thenReturn(
                new MealPlanOptimizer.SlotResult(cand(recipe(6, "R6"), 600, 40, 12, "3.00", true, true),
                        List.of("2026-09-09: 600 kcal is below the calorie band.")));
        when(mealPlanSlotRepository.findByMealPlanId(99L)).thenReturn(List.of());

        MealPlanResponse res = service.selectForWeek(3L, new SelectPlanRequest(WEEK.plusWeeks(1)));

        verify(mealPlanOptimizer, times(1)).selectReplacement(anyList(), any());
        verify(mealPlanSlotRepository).save(any(MealPlanSlot.class));
        assertThat(res.warnings()).isNotEmpty();
    }
}
