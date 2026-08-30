package com.yourname.mealassistant.mealplan.optimizer;

import com.yourname.mealassistant.recipe.Recipe;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The single constraint engine for meal planning. RecipeRankingService stays purely structural;
// all budget/calorie/protein/fiber logic lives here. Given a pool of candidate recipes — each
// already annotated with serving-scaled nutrition and an estimated cost — it fills a plan's
// slots, and also handles single-slot replacement for "swap Friday lunch".
//
// Algorithm (Decided): WEIGHTED SCORE + CUTOFF, greedy slot-by-slot.
//   - "cutoff": a candidate is rejected outright if adding it would break a hard limit —
//     overshoot the day's calorie band, or push cost-complete spend over the weekly budget.
//   - "score": among the survivors, pick the one that best moves the running per-day totals
//     toward the targets, with a small penalty for cost. Weights are the W_* constants below.
//   - When no candidate survives the cutoff, the least-bad one is taken and a warning is added
//     (Decided: closest-fit + warning, never a hard failure).
//
// Constraint rules (Decided):
//   - calories: each DAY's meals must SUM to within +/-100 kcal of the daily target; individual
//     meals just aim for an even share (a light breakfast + big dinner is fine).
//   - protein & fiber: each day should reach at least MIN_MACRO_FRACTION of the daily target
//     (floors, not bands); falling short produces a warning, not a rejection.
//   - budget: weekly, across the whole plan; only cost-complete candidates count toward it.
//   - a nutrition-incomplete or cost-incomplete candidate is still eligible — its partial
//     figures are used and the slot/plan is flagged downstream.
//
// The W_* weights and MIN_MACRO_FRACTION are deliberately simple constants — tune them here;
// they are not exposed as configuration.
@Service
public class MealPlanOptimizer {

    public static final double CALORIE_TOLERANCE_KCAL = 100.0;
    public static final double MIN_MACRO_FRACTION = 0.90;

    // Relative weights in the per-candidate score (all terms are normalised to ~0..1 so the
    // weights are the whole story): calorie fit dominates, macros matter less, cost is a nudge.
    static final double W_CALORIE = 1.0;
    static final double W_PROTEIN = 0.4;
    static final double W_FIBER = 0.4;
    static final double W_COST = 0.3;

    // Fill every slot in `layout` from `candidates`. A recipe is used for one contiguous run of
    // slots: normally just one, but up to layout.maxBatchSize() CONSECUTIVE slots of the SAME
    // meal type when meal-prep batching is on (Decided: "cook once, eat 2-3 times"). Meal types
    // are filled one at a time across all days so a batch forward-fills into days whose earlier
    // meals are already fixed.
    public PlanResult selectPlan(List<RecipeCandidate> candidates, PlanConstraints constraints, PlanLayout layout) {
        List<LocalDate> dates = layout.dates();
        List<String> mealTypes = layout.mealTypes();
        int nDays = dates.size();
        int nMeals = mealTypes.size();
        int mealsPerDay = Math.max(1, nMeals);
        int maxBatch = Math.max(1, layout.maxBatchSize() == null ? 1 : layout.maxBatchSize());

        double perSlotCalories = safe(constraints.dailyCalorieTarget()) / mealsPerDay;
        double perSlotProtein = safe(constraints.dailyProteinTarget()) / mealsPerDay;
        double perSlotFiber = safe(constraints.dailyFiberTarget()) / mealsPerDay;
        double dailyCalorieCeil = safe(constraints.dailyCalorieTarget()) + CALORIE_TOLERANCE_KCAL;
        BigDecimal budget = constraints.weeklyBudget();

        RecipeCandidate[][] grid = new RecipeCandidate[nDays][nMeals];
        Set<Long> used = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;

        for (int m = 0; m < nMeals; m++) {
            for (int d = 0; d < nDays; d++) {
                if (grid[d][m] != null) continue; // already filled by an earlier batch

                double calorieCeil = dailyCalorieCeil - committedCalories(grid, d, m);

                RecipeCandidate pick = null;
                double bestScore = Double.MAX_VALUE;
                RecipeCandidate fallback = null;
                double fallbackScore = Double.MAX_VALUE;

                for (RecipeCandidate c : candidates) {
                    if (used.contains(c.recipe().getId())) continue;

                    double score = score(c, perSlotCalories, perSlotProtein, perSlotFiber, budget);
                    if (score < fallbackScore) {
                        fallbackScore = score;
                        fallback = c;
                    }

                    boolean overCalories = safe(c.estimatedCalories()) > calorieCeil;
                    boolean overBudget = c.costComplete() && budget != null
                            && spent.add(nz(c.estimatedCost())).compareTo(budget) > 0;
                    if (overCalories || overBudget) continue;

                    if (score < bestScore) {
                        bestScore = score;
                        pick = c;
                    }
                }

                if (pick == null) pick = fallback;
                if (pick == null) {
                    warnings.add("No recipes left to fill " + mealTypes.get(m) + " on " + dates.get(d) + ".");
                    continue;
                }

                // assign here, then batch forward across consecutive same-meal-type days that
                // still fit their own calorie ceiling
                int batched = 0;
                for (int k = 0; k < maxBatch && d + k < nDays; k++) {
                    if (grid[d + k][m] != null) break;
                    if (safe(pick.estimatedCalories()) > dailyCalorieCeil - committedCalories(grid, d + k, m)) break;
                    grid[d + k][m] = pick;
                    batched++;
                }
                used.add(pick.recipe().getId());
                if (pick.costComplete()) {
                    // budget cutoff above only counted one serving; a batch's extra servings can
                    // nudge past budget -> caught by the plan-level warning below.
                    spent = spent.add(nz(pick.estimatedCost()).multiply(BigDecimal.valueOf(batched)));
                }
            }
        }

        List<SlotAssignment> assignments = new ArrayList<>();
        for (int d = 0; d < nDays; d++) {
            double dayCalories = 0, dayProtein = 0, dayFiber = 0;
            for (int m = 0; m < nMeals; m++) {
                RecipeCandidate c = grid[d][m];
                if (c == null) continue;
                assignments.add(new SlotAssignment(dates.get(d), mealTypes.get(m), c));
                dayCalories += safe(c.estimatedCalories());
                dayProtein += safe(c.estimatedProtein());
                dayFiber += safe(c.estimatedFiber());
            }
            addDayWarnings(warnings, dates.get(d), constraints, dayCalories, dayProtein, dayFiber);
        }

        if (budget != null && spent.compareTo(budget) > 0) {
            warnings.add("Plan cost (" + spent + ") exceeds the weekly budget (" + budget + ").");
        }
        return new PlanResult(assignments, warnings);
    }

    // Calories already committed on `day` by meal types other than `exceptMealType`.
    private double committedCalories(RecipeCandidate[][] grid, int day, int exceptMealType) {
        double sum = 0;
        for (int m = 0; m < grid[day].length; m++) {
            if (m == exceptMealType) continue;
            RecipeCandidate c = grid[day][m];
            if (c != null) sum += safe(c.estimatedCalories());
        }
        return sum;
    }

    // Pick a replacement for one slot given the room left by the slots that stay put.
    // Decided: if nothing fits the room / budget, return the closest candidate + a warning.
    public SlotResult selectReplacement(List<RecipeCandidate> candidates, SlotConstraints remaining) {
        List<RecipeCandidate> pool = candidates.stream()
                .filter(c -> remaining.excludeRecipeIds() == null
                        || !remaining.excludeRecipeIds().contains(c.recipe().getId()))
                .toList();
        if (pool.isEmpty()) {
            throw new IllegalStateException("No candidate recipes available for slot replacement.");
        }

        double low = safe(remaining.calorieRoomLow());
        double high = safe(remaining.calorieRoomHigh());
        double mid = (low + high) / 2.0;

        RecipeCandidate best = null;
        double bestDist = Double.MAX_VALUE;
        RecipeCandidate closest = null;
        double closestDist = Double.MAX_VALUE;

        for (RecipeCandidate c : pool) {
            double cal = safe(c.estimatedCalories());
            double dist = Math.abs(cal - mid);
            if (dist < closestDist) {
                closestDist = dist;
                closest = c;
            }

            boolean inRoom = cal >= low && cal <= high;
            boolean withinBudget = !c.costComplete() || remaining.remainingWeeklyBudget() == null
                    || nz(c.estimatedCost()).compareTo(remaining.remainingWeeklyBudget()) <= 0;
            if (inRoom && withinBudget && dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }

        List<String> warnings = new ArrayList<>();
        if (best == null) {
            best = closest;
            warnings.add("No replacement fits the remaining calorie room / budget for this slot; picked the closest.");
        }
        return new SlotResult(best, warnings);
    }

    // --- scoring ---

    private double score(RecipeCandidate c, double perSlotCalories, double perSlotProtein,
                         double perSlotFiber, BigDecimal weeklyBudget) {
        double calTerm = perSlotCalories <= 0 ? 0
                : Math.abs(safe(c.estimatedCalories()) - perSlotCalories) / perSlotCalories;
        double proteinTerm = shortfallFraction(perSlotProtein, safe(c.estimatedProtein()));
        double fiberTerm = shortfallFraction(perSlotFiber, safe(c.estimatedFiber()));
        double costTerm = (weeklyBudget == null || weeklyBudget.signum() <= 0 || !c.costComplete())
                ? 0
                : nz(c.estimatedCost()).doubleValue() / weeklyBudget.doubleValue();

        return W_CALORIE * calTerm + W_PROTEIN * proteinTerm + W_FIBER * fiberTerm + W_COST * costTerm;
    }

    // Only penalises being UNDER the target (protein/fiber are floors).
    private static double shortfallFraction(double target, double value) {
        if (target <= 0) return 0;
        return Math.max(0, target - value) / target;
    }

    private void addDayWarnings(List<String> warnings, LocalDate date, PlanConstraints constraints,
                                double dayCalories, double dayProtein, double dayFiber) {
        double calTarget = safe(constraints.dailyCalorieTarget());
        if (calTarget > 0) {
            if (dayCalories < calTarget - CALORIE_TOLERANCE_KCAL) {
                warnings.add(date + ": " + Math.round(dayCalories) + " kcal is below the calorie band.");
            } else if (dayCalories > calTarget + CALORIE_TOLERANCE_KCAL) {
                warnings.add(date + ": " + Math.round(dayCalories) + " kcal is above the calorie band.");
            }
        }
        if (safe(constraints.dailyProteinTarget()) > 0
                && dayProtein < MIN_MACRO_FRACTION * constraints.dailyProteinTarget()) {
            warnings.add(date + ": protein " + Math.round(dayProtein) + " g is below target.");
        }
        if (safe(constraints.dailyFiberTarget()) > 0
                && dayFiber < MIN_MACRO_FRACTION * constraints.dailyFiberTarget()) {
            warnings.add(date + ": fiber " + Math.round(dayFiber) + " g is below target.");
        }
    }

    private static double safe(Double d) {
        return d == null ? 0.0 : d;
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    // --- value types ---

    // A recipe plus what the optimizer needs that Recipe doesn't hold: serving-scaled nutrition
    // and estimated cost. costComplete / nutritionComplete are false when the respective figure
    // is partial (some ingredient had no price / no macros) — such candidates are still eligible.
    public record RecipeCandidate(
            Recipe recipe,
            Double estimatedCalories,
            Double estimatedProtein,
            Double estimatedFiber,
            BigDecimal estimatedCost,
            boolean costComplete,
            boolean nutritionComplete) {
    }

    // The four targets, snapshot from UserPreference by MealPlanService.
    public record PlanConstraints(
            Double dailyCalorieTarget,
            Double dailyProteinTarget,
            Double dailyFiberTarget,
            BigDecimal weeklyBudget) {
    }

    // The slot grid to fill. dates = weekStartDate + 0..n; mealTypes = e.g.
    // ["BREAKFAST","LUNCH","DINNER"]; servingsPerMeal is informational here (candidates are
    // already serving-scaled by MealPlanService); maxBatchSize is UserPreference.mealPrepBatchSize
    // (1 = no batching).
    public record PlanLayout(List<LocalDate> dates, List<String> mealTypes, Double servingsPerMeal,
                             Integer maxBatchSize) {
    }

    // Room left for one slot by the slots that stay put (see MealPlanService.replaceSlot):
    // the calorie window is [dailyTarget +/- tolerance] minus the other same-day slots'
    // calories; remainingWeeklyBudget is the snapshot budget minus the other slots' cost.
    public record SlotConstraints(
            Double calorieRoomLow,
            Double calorieRoomHigh,
            BigDecimal remainingWeeklyBudget,
            List<Long> excludeRecipeIds) {
    }

    public record SlotAssignment(LocalDate date, String mealType, RecipeCandidate candidate) {
    }

    public record PlanResult(List<SlotAssignment> assignments, List<String> warnings) {
    }

    public record SlotResult(RecipeCandidate chosen, List<String> warnings) {
    }
}
