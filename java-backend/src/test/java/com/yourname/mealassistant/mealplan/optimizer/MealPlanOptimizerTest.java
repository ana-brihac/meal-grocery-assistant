package com.yourname.mealassistant.mealplan.optimizer;

import com.yourname.mealassistant.recipe.Recipe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure logic, no Spring — MealPlanOptimizer has no dependencies.
class MealPlanOptimizerTest {

    private final MealPlanOptimizer optimizer = new MealPlanOptimizer();

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final List<String> BLD = List.of("BREAKFAST", "LUNCH", "DINNER");

    private static Recipe recipe(long id) {
        Recipe r = new Recipe();
        r.setId(id);
        r.setName("R" + id);
        return r;
    }

    private static MealPlanOptimizer.RecipeCandidate cand(long id, double cal, double protein, double fiber, String cost) {
        return new MealPlanOptimizer.RecipeCandidate(recipe(id), cal, protein, fiber, new BigDecimal(cost), true, true);
    }

    private static MealPlanOptimizer.PlanConstraints constraints() {
        return new MealPlanOptimizer.PlanConstraints(1600.0, 60.0, 15.0, new BigDecimal("50"));
    }

    private static MealPlanOptimizer.PlanLayout oneDay() {
        return new MealPlanOptimizer.PlanLayout(List.of(DAY), BLD, 1.0, 1);
    }

    // ---- selectPlan ----

    @Test
    void selectPlan_fillsEverySlotWithDistinctRecipes_noWarningsWhenInBand() {
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(
                cand(1, 500, 20, 5, "2.00"),
                cand(2, 550, 25, 6, "2.50"),
                cand(3, 520, 22, 5, "2.20"));

        MealPlanOptimizer.PlanResult result = optimizer.selectPlan(candidates, constraints(), oneDay());

        assertThat(result.assignments()).hasSize(3);
        assertThat(result.assignments().stream().map(a -> a.candidate().recipe().getId()).distinct()).hasSize(3);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void selectPlan_respectsWeeklyBudget_skipsUnaffordableRecipe() {
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(
                cand(1, 530, 20, 5, "3.00"),
                cand(2, 530, 20, 5, "3.00"),
                cand(3, 530, 20, 5, "3.00"),
                cand(4, 530, 20, 5, "99.00"));

        MealPlanOptimizer.PlanResult result = optimizer.selectPlan(candidates,
                new MealPlanOptimizer.PlanConstraints(1600.0, 60.0, 15.0, new BigDecimal("10")),
                oneDay());

        assertThat(result.assignments().stream().map(a -> a.candidate().recipe().getId()))
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void selectPlan_warnsWhenDayFallsBelowCalorieBand() {
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(
                cand(1, 200, 20, 5, "1.00"),
                cand(2, 210, 20, 5, "1.00"),
                cand(3, 205, 20, 5, "1.00"));

        MealPlanOptimizer.PlanResult result = optimizer.selectPlan(candidates, constraints(), oneDay());

        assertThat(result.assignments()).hasSize(3);
        assertThat(result.warnings()).anyMatch(w -> w.contains("below the calorie band"));
    }

    @Test
    void selectPlan_noCandidates_returnsEmptyAssignmentsWithWarnings() {
        MealPlanOptimizer.PlanResult result = optimizer.selectPlan(List.of(), constraints(), oneDay());

        assertThat(result.assignments()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void selectPlan_batchSize3_reusesOneRecipeAcrossConsecutiveSameMealSlots() {
        // 7 candidates, all near the per-slot calorie share so any can fill any slot
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(
                cand(1, 520, 22, 5, "2.00"),
                cand(2, 525, 22, 5, "2.00"),
                cand(3, 530, 22, 5, "2.00"),
                cand(4, 535, 22, 5, "2.00"),
                cand(5, 540, 22, 5, "2.00"),
                cand(6, 545, 22, 5, "2.00"),
                cand(7, 515, 22, 5, "2.00"));

        MealPlanOptimizer.PlanLayout layout = new MealPlanOptimizer.PlanLayout(
                List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)),
                BLD, 1.0, 3);

        MealPlanOptimizer.PlanResult result = optimizer.selectPlan(candidates,
                new MealPlanOptimizer.PlanConstraints(1600.0, 60.0, 15.0, new BigDecimal("100")), layout);

        assertThat(result.assignments()).hasSize(9); // 3 days x 3 meals

        // each meal type is the SAME recipe on all three days (cook once, eat 3x)
        for (String meal : BLD) {
            List<Long> ids = result.assignments().stream()
                    .filter(a -> a.mealType().equals(meal))
                    .map(a -> a.candidate().recipe().getId())
                    .toList();
            assertThat(ids).hasSize(3);
            assertThat(ids).containsOnly(ids.get(0));
        }

        // and the three meal types use three different recipes
        assertThat(result.assignments().stream()
                .map(a -> a.candidate().recipe().getId()).distinct()).hasSize(3);
    }

    // ---- selectReplacement ----

    @Test
    void selectReplacement_picksCandidateInRoomAndNotExcluded() {
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(
                cand(1, 500, 20, 5, "2.00"),
                cand(2, 800, 20, 5, "2.00"),
                cand(3, 520, 20, 5, "2.00"));

        MealPlanOptimizer.SlotResult result = optimizer.selectReplacement(candidates,
                new MealPlanOptimizer.SlotConstraints(450.0, 600.0, new BigDecimal("20"), List.of(1L)));

        assertThat(result.chosen().recipe().getId()).isEqualTo(3L);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void selectReplacement_nothingFitsRoom_returnsClosestWithWarning() {
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(
                cand(1, 900, 20, 5, "2.00"),
                cand(2, 950, 20, 5, "2.00"));

        MealPlanOptimizer.SlotResult result = optimizer.selectReplacement(candidates,
                new MealPlanOptimizer.SlotConstraints(400.0, 600.0, new BigDecimal("20"), List.of()));

        assertThat(result.chosen().recipe().getId()).isEqualTo(1L);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void selectReplacement_everyCandidateExcluded_throws() {
        List<MealPlanOptimizer.RecipeCandidate> candidates = List.of(cand(1, 500, 20, 5, "2.00"));

        assertThatThrownBy(() -> optimizer.selectReplacement(candidates,
                new MealPlanOptimizer.SlotConstraints(400.0, 600.0, null, List.of(1L))))
                .isInstanceOf(IllegalStateException.class);
    }
}
