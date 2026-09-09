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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// Orchestrates meal planning: build candidates, snapshot the user's targets, run
// MealPlanOptimizer, persist the MealPlan + its MealPlanSlots, and serve them back. Also owns
// history browsing, single-slot replacement, and clone-on-select. No constraint logic of its own
// — that all lives in MealPlanOptimizer.
@Service
public class MealPlanService {

    private static final int DEFAULT_DAYS = 7;
    private static final List<String> DEFAULT_MEAL_TYPES = List.of("BREAKFAST", "LUNCH", "DINNER");
    private static final double DEFAULT_SERVINGS = 1.0;

    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;
    private final IngredientPriceService ingredientPriceService;
    private final NutritionService nutritionService;
    private final UserPreferenceService userPreferenceService;
    private final MealPlanOptimizer mealPlanOptimizer;
    private final MealPlanRepository mealPlanRepository;
    private final MealPlanSlotRepository mealPlanSlotRepository;
    private final GroceryListRepository groceryListRepository;

    public MealPlanService(RecipeRepository recipeRepository,
                           RecipeIngredientRepository recipeIngredientRepository,
                           IngredientPriceService ingredientPriceService,
                           NutritionService nutritionService,
                           UserPreferenceService userPreferenceService,
                           MealPlanOptimizer mealPlanOptimizer,
                           MealPlanRepository mealPlanRepository,
                           MealPlanSlotRepository mealPlanSlotRepository,
                           GroceryListRepository groceryListRepository) {
        this.recipeRepository = recipeRepository;
        this.recipeIngredientRepository = recipeIngredientRepository;
        this.ingredientPriceService = ingredientPriceService;
        this.nutritionService = nutritionService;
        this.userPreferenceService = userPreferenceService;
        this.mealPlanOptimizer = mealPlanOptimizer;
        this.mealPlanRepository = mealPlanRepository;
        this.mealPlanSlotRepository = mealPlanSlotRepository;
        this.groceryListRepository = groceryListRepository;
    }

    // Decided: candidate pool = ALL recipes; the optimizer filters. Targets + budget
    // are snapshot onto the plan row. Default layout is BREAKFAST/LUNCH/DINNER x 7 days from
    // weekStartDate; the request may override days / mealTypes / servingsPerMeal.
    public MealPlanResponse generatePlan(MealPlanRequest request) {
        if (request == null || request.weekStartDate() == null) {
            throw new BadRequestException("weekStartDate is required");
        }
        int days = request.days() != null && request.days() > 0 ? request.days() : DEFAULT_DAYS;
        List<String> mealTypes = request.mealTypes() != null && !request.mealTypes().isEmpty()
                ? request.mealTypes() : DEFAULT_MEAL_TYPES;
        double servings = request.servingsPerMeal() != null && request.servingsPerMeal() > 0
                ? request.servingsPerMeal() : DEFAULT_SERVINGS;

        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            dates.add(request.weekStartDate().plusDays(i));
        }

        UserPreference prefs = userPreferenceService.getPreferences();
        int batchSize = prefs.getMealPrepBatchSize() == null ? 1 : Math.max(1, prefs.getMealPrepBatchSize());
        List<MealPlanOptimizer.RecipeCandidate> candidates = buildCandidates(Set.of(), servings);

        MealPlanOptimizer.PlanResult result = mealPlanOptimizer.selectPlan(
                candidates,
                new MealPlanOptimizer.PlanConstraints(prefs.getCalories(), prefs.getProtein(),
                        prefs.getFiber(), prefs.getWeeklyBudget()),
                new MealPlanOptimizer.PlanLayout(dates, mealTypes, servings, batchSize));

        MealPlan plan = new MealPlan();
        plan.setWeekStartDate(request.weekStartDate());
        plan.setStatus(MealPlan.STATUS_DRAFT);
        plan.setCalorieTargetSnapshot(prefs.getCalories());
        plan.setProteinTargetSnapshot(prefs.getProtein());
        plan.setFiberTargetSnapshot(prefs.getFiber());
        plan.setWeeklyBudgetSnapshot(prefs.getWeeklyBudget());
        plan = mealPlanRepository.save(plan);

        List<MealPlanSlot> slots = new ArrayList<>();
        for (MealPlanOptimizer.SlotAssignment a : result.assignments()) {
            slots.add(newSlot(plan.getId(), a.date(), a.mealType(), servings, a.candidate()));
        }
        slots = mealPlanSlotRepository.saveAll(slots);

        return toResponse(plan, slots, result.warnings());
    }

    public List<MealPlanResponse> getHistory() {
        return mealPlanRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> toResponse(p, mealPlanSlotRepository.findByMealPlanId(p.getId()), List.of()))
                .toList();
    }

    public MealPlanResponse getPlan(Long planId) {
        MealPlan plan = mealPlanRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Meal plan not found: " + planId));
        return toResponse(plan, mealPlanSlotRepository.findByMealPlanId(planId), List.of());
    }

    // Swap a single slot for one that still fits the rest of the plan. "Fits" = this day's
    // remaining calorie room + the plan's leftover weekly budget; the replacement is never a
    // recipe already used elsewhere in the plan; any grocery list for this plan is marked stale.
    public MealPlanResponse replaceSlot(Long planId, Long slotId, SlotReplacementRequest request) {
        MealPlan plan = mealPlanRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Meal plan not found: " + planId));
        List<MealPlanSlot> slots = mealPlanSlotRepository.findByMealPlanId(planId);
        MealPlanSlot target = slots.stream().filter(s -> s.getId().equals(slotId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Slot " + slotId + " is not in plan " + planId));

        Set<Long> extraExclude = request != null && request.excludeRecipeIds() != null
                ? new HashSet<>(request.excludeRecipeIds()) : new HashSet<>();

        List<String> warnings = reoptimiseSlot(plan, slots, target, extraExclude);

        List<GroceryListItem> groceryList = groceryListRepository.findByMealPlanId(planId);
        if (!groceryList.isEmpty()) {
            groceryList.forEach(i -> i.setStale(true));
            groceryListRepository.saveAll(groceryList);
        }

        return toResponse(plan, mealPlanSlotRepository.findByMealPlanId(planId), warnings);
    }

    // "Choose this historical plan for a week": clone it into a new plan for the target week,
    // then auto-swap any copied meals that no longer fit the CURRENT targets. The source plan is
    // left untouched.
    public MealPlanResponse selectForWeek(Long planId, SelectPlanRequest request) {
        if (request == null || request.weekStartDate() == null) {
            throw new BadRequestException("weekStartDate is required");
        }
        MealPlan source = mealPlanRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Meal plan not found: " + planId));
        List<MealPlanSlot> sourceSlots = mealPlanSlotRepository.findByMealPlanId(planId);

        UserPreference prefs = userPreferenceService.getPreferences();
        LocalDate newWeek = request.weekStartDate();
        long shift = source.getWeekStartDate() == null ? 0
                : ChronoUnit.DAYS.between(source.getWeekStartDate(), newWeek);

        for (MealPlan other : mealPlanRepository.findByWeekStartDateAndStatus(newWeek, MealPlan.STATUS_SELECTED)) {
            other.setStatus(MealPlan.STATUS_DRAFT);
            mealPlanRepository.save(other);
        }

        MealPlan clone = new MealPlan();
        clone.setWeekStartDate(newWeek);
        clone.setStatus(MealPlan.STATUS_SELECTED);
        clone.setSourcePlanId(planId);
        clone.setCalorieTargetSnapshot(prefs.getCalories());
        clone.setProteinTargetSnapshot(prefs.getProtein());
        clone.setFiberTargetSnapshot(prefs.getFiber());
        clone.setWeeklyBudgetSnapshot(prefs.getWeeklyBudget());
        MealPlan savedClone = mealPlanRepository.save(clone);

        List<MealPlanSlot> newSlots = new ArrayList<>();
        for (MealPlanSlot s : sourceSlots) {
            MealPlanSlot c = new MealPlanSlot();
            c.setMealPlanId(savedClone.getId());
            c.setSlotDate(s.getSlotDate() == null ? null : s.getSlotDate().plusDays(shift));
            c.setMealType(s.getMealType());
            c.setRecipeId(s.getRecipeId());
            c.setServings(s.getServings());
            c.setEstimatedCalories(s.getEstimatedCalories());
            c.setEstimatedProtein(s.getEstimatedProtein());
            c.setEstimatedFiber(s.getEstimatedFiber());
            c.setEstimatedCost(s.getEstimatedCost());
            c.setCostComplete(s.getCostComplete());
            c.setNutritionComplete(s.getNutritionComplete());
            newSlots.add(c);
        }
        newSlots = mealPlanSlotRepository.saveAll(newSlots);

        List<String> warnings = new ArrayList<>();
        Map<LocalDate, List<MealPlanSlot>> byDay = new TreeMap<>(newSlots.stream()
                .filter(s -> s.getSlotDate() != null)
                .collect(Collectors.groupingBy(MealPlanSlot::getSlotDate)));

        for (Map.Entry<LocalDate, List<MealPlanSlot>> day : byDay.entrySet()) {
            List<MealPlanSlot> daySlots = day.getValue();
            int guard = 0;
            while (!dayWithinTargets(daySlots, savedClone) && guard < daySlots.size()) {
                MealPlanSlot worst = worstSlot(daySlots, savedClone);
                warnings.addAll(reoptimiseSlot(savedClone, newSlots, worst, new HashSet<>()));
                guard++;
            }
            if (!dayWithinTargets(daySlots, savedClone)) {
                warnings.add(day.getKey() + ": could not fully match current targets after reuse.");
            }
        }

        return toResponse(savedClone, mealPlanSlotRepository.findByMealPlanId(savedClone.getId()), warnings);
    }

    // --- internals ---

    // Swaps `slot` for a better-fitting recipe given the OTHER slots on its day and the plan's
    // budget. Mutates and saves `slot` (a live element of `allSlots`). Returns optimizer warnings.
    private List<String> reoptimiseSlot(MealPlan plan, List<MealPlanSlot> allSlots, MealPlanSlot slot,
                                        Set<Long> extraExclude) {
        double servings = slot.getServings() != null ? slot.getServings() : DEFAULT_SERVINGS;

        double sameDayOtherCalories = allSlots.stream()
                .filter(s -> !s.getId().equals(slot.getId()) && Objects.equals(s.getSlotDate(), slot.getSlotDate()))
                .map(MealPlanSlot::getEstimatedCalories)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double calTarget = plan.getCalorieTargetSnapshot() == null ? 0.0 : plan.getCalorieTargetSnapshot();
        double low = calTarget - MealPlanOptimizer.CALORIE_TOLERANCE_KCAL - sameDayOtherCalories;
        double high = calTarget + MealPlanOptimizer.CALORIE_TOLERANCE_KCAL - sameDayOtherCalories;

        BigDecimal remainingBudget = null;
        if (plan.getWeeklyBudgetSnapshot() != null) {
            BigDecimal spentElsewhere = allSlots.stream()
                    .filter(s -> !s.getId().equals(slot.getId())
                            && Boolean.TRUE.equals(s.getCostComplete()) && s.getEstimatedCost() != null)
                    .map(MealPlanSlot::getEstimatedCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            remainingBudget = plan.getWeeklyBudgetSnapshot().subtract(spentElsewhere);
        }

        Set<Long> exclude = new HashSet<>(extraExclude);
        allSlots.forEach(s -> exclude.add(s.getRecipeId()));

        List<MealPlanOptimizer.RecipeCandidate> candidates = buildCandidates(exclude, servings);
        if (candidates.isEmpty()) {
            return List.of("No alternative recipe available for the " + slot.getMealType()
                    + " slot on " + slot.getSlotDate() + ".");
        }

        MealPlanOptimizer.SlotResult result = mealPlanOptimizer.selectReplacement(candidates,
                new MealPlanOptimizer.SlotConstraints(low, high, remainingBudget, List.copyOf(exclude)));

        MealPlanOptimizer.RecipeCandidate chosen = result.chosen();
        slot.setRecipeId(chosen.recipe().getId());
        slot.setEstimatedCalories(chosen.estimatedCalories());
        slot.setEstimatedProtein(chosen.estimatedProtein());
        slot.setEstimatedFiber(chosen.estimatedFiber());
        slot.setEstimatedCost(chosen.estimatedCost());
        slot.setCostComplete(chosen.costComplete());
        slot.setNutritionComplete(chosen.nutritionComplete());
        mealPlanSlotRepository.save(slot);

        return result.warnings();
    }

    private List<MealPlanOptimizer.RecipeCandidate> buildCandidates(Set<Long> excludeRecipeIds, double servings) {
        List<MealPlanOptimizer.RecipeCandidate> out = new ArrayList<>();
        for (Recipe recipe : recipeRepository.findAll()) {
            if (excludeRecipeIds.contains(recipe.getId())) continue;
            RecipeNutrition n = nutritionService.computeRecipeNutrition(recipe.getId(), servings);
            RecipeCost c = estimateRecipeCost(recipe.getId(), servings);
            out.add(new MealPlanOptimizer.RecipeCandidate(recipe, n.calories(), n.protein(), n.fiber(),
                    c.cost(), c.complete(), n.complete()));
        }
        return out;
    }

    private RecipeCost estimateRecipeCost(Long recipeId, double servings) {
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (RecipeIngredient ingredient : recipeIngredientRepository.findByRecipeId(recipeId)) {
            double grams = (ingredient.getQuantity() == null ? 0.0 : ingredient.getQuantity()) * servings;
            Optional<BigDecimal> cost = ingredientPriceService.estimateIngredientCost(
                    ingredient.getIngredientName(), grams);
            if (cost.isPresent()) total = total.add(cost.get());
            else complete = false;
        }
        return new RecipeCost(total, complete);
    }

    private MealPlanSlot newSlot(Long planId, LocalDate date, String mealType, double servings,
                                 MealPlanOptimizer.RecipeCandidate c) {
        MealPlanSlot s = new MealPlanSlot();
        s.setMealPlanId(planId);
        s.setSlotDate(date);
        s.setMealType(mealType);
        s.setRecipeId(c.recipe().getId());
        s.setServings(servings);
        s.setEstimatedCalories(c.estimatedCalories());
        s.setEstimatedProtein(c.estimatedProtein());
        s.setEstimatedFiber(c.estimatedFiber());
        s.setEstimatedCost(c.estimatedCost());
        s.setCostComplete(c.costComplete());
        s.setNutritionComplete(c.nutritionComplete());
        return s;
    }

    private boolean dayWithinTargets(List<MealPlanSlot> daySlots, MealPlan plan) {
        double cal = sum(daySlots, MealPlanSlot::getEstimatedCalories);
        double protein = sum(daySlots, MealPlanSlot::getEstimatedProtein);
        double fiber = sum(daySlots, MealPlanSlot::getEstimatedFiber);

        Double ct = plan.getCalorieTargetSnapshot();
        if (ct != null && ct > 0 && (cal < ct - MealPlanOptimizer.CALORIE_TOLERANCE_KCAL
                || cal > ct + MealPlanOptimizer.CALORIE_TOLERANCE_KCAL)) {
            return false;
        }
        Double pt = plan.getProteinTargetSnapshot();
        if (pt != null && pt > 0 && protein < MealPlanOptimizer.MIN_MACRO_FRACTION * pt) {
            return false;
        }
        Double ft = plan.getFiberTargetSnapshot();
        return ft == null || ft <= 0 || fiber >= MealPlanOptimizer.MIN_MACRO_FRACTION * ft;
    }

    private MealPlanSlot worstSlot(List<MealPlanSlot> daySlots, MealPlan plan) {
        double share = plan.getCalorieTargetSnapshot() == null ? 0.0
                : plan.getCalorieTargetSnapshot() / Math.max(1, daySlots.size());
        return daySlots.stream()
                .max(Comparator.comparingDouble((MealPlanSlot s) ->
                        Math.abs((s.getEstimatedCalories() == null ? 0.0 : s.getEstimatedCalories()) - share)))
                .orElse(daySlots.get(0));
    }

    private MealPlanResponse toResponse(MealPlan plan, List<MealPlanSlot> slots, List<String> warnings) {
        List<MealPlanResponse.PlannedSlot> slotDtos = slots.stream()
                .sorted(Comparator.comparing(MealPlanSlot::getSlotDate,
                                Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                        .thenComparing(MealPlanSlot::getId, Comparator.nullsLast(Comparator.<Long>naturalOrder())))
                .map(s -> new MealPlanResponse.PlannedSlot(
                        s.getId(), s.getSlotDate(), s.getMealType(), s.getRecipeId(),
                        recipeRepository.findById(s.getRecipeId()).map(Recipe::getName).orElse(null),
                        s.getServings(), s.getEstimatedCalories(), s.getEstimatedProtein(),
                        s.getEstimatedFiber(), s.getEstimatedCost(),
                        Boolean.TRUE.equals(s.getCostComplete()),
                        Boolean.TRUE.equals(s.getNutritionComplete())))
                .toList();

        BigDecimal totalCost = slots.stream()
                .filter(s -> Boolean.TRUE.equals(s.getCostComplete()) && s.getEstimatedCost() != null)
                .map(MealPlanSlot::getEstimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean costIncomplete = slots.stream().anyMatch(s -> !Boolean.TRUE.equals(s.getCostComplete()));
        boolean nutritionIncomplete = slots.stream().anyMatch(s -> !Boolean.TRUE.equals(s.getNutritionComplete()));

        return new MealPlanResponse(
                plan.getId(), plan.getStatus(), plan.getWeekStartDate(), plan.getSourcePlanId(),
                new MealPlanResponse.Targets(plan.getCalorieTargetSnapshot(), plan.getProteinTargetSnapshot(),
                        plan.getFiberTargetSnapshot(), plan.getWeeklyBudgetSnapshot()),
                slotDtos, totalCost, costIncomplete, nutritionIncomplete,
                warnings == null ? List.of() : warnings.stream().distinct().toList());
    }

    private static double sum(List<MealPlanSlot> slots, Function<MealPlanSlot, Double> field) {
        return slots.stream().map(field).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    }

    private record RecipeCost(BigDecimal cost, boolean complete) {
    }
}
