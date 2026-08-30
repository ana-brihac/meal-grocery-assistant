package com.yourname.mealassistant.mealplan;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

// One meal in a MealPlan: a concrete date + meal type holding a single recipe, plus that
// recipe's contribution toward the plan totals (snapshot so the "why this recipe" breakdown and
// the history view don't have to recompute against data that may have moved).
//
// Decided: slots are keyed by a concrete slotDate (the plan's weekStartDate + 0..6),
//   not a day-of-week enum — "change Friday lunch" means the Friday of that plan's week.
// Decided: default layout is BREAKFAST / LUNCH / DINNER x 7 days = 21 slots. mealType
//   is a free-text String column (not an enum) so the layout isn't locked in the schema — see
//   MealPlanRequest.
// Decided: raw FK Longs (mealPlanId, recipeId), matching RecipeIngredient's style —
//   not @ManyToOne references.
// Decided: costComplete is false when at least one of the recipe's ingredients had no
//   row in ingredient_price at generation time — the estimatedCost is then a partial figure and
//   the slot is excluded from the plan's budget check (see MealPlanOptimizer that decision).
@Entity
@Table(name = "meal_plan_slot")
public class MealPlanSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meal_plan_id")
    private Long mealPlanId;

    @Column(name = "slot_date")
    private LocalDate slotDate;

    @Column(name = "meal_type")
    private String mealType;

    @Column(name = "recipe_id")
    private Long recipeId;

    private Double servings;

    // Per-slot contribution snapshot. Macros -> Double / DOUBLE PRECISION; cost -> BigDecimal /
    // NUMERIC.
    @Column(name = "estimated_calories")
    private Double estimatedCalories;

    @Column(name = "estimated_protein")
    private Double estimatedProtein;

    @Column(name = "estimated_fiber")
    private Double estimatedFiber;

    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(name = "cost_complete")
    private Boolean costComplete = false;

    // false if some ingredient's macros were still unknown after the cache -> USDA -> AI chain
    // (see NutritionService.computeRecipeNutrition). estimatedCalories/protein/fiber are then a
    // partial figure.
    @Column(name = "nutrition_complete")
    private Boolean nutritionComplete = false;

    public MealPlanSlot() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMealPlanId() { return mealPlanId; }
    public void setMealPlanId(Long mealPlanId) { this.mealPlanId = mealPlanId; }

    public LocalDate getSlotDate() { return slotDate; }
    public void setSlotDate(LocalDate slotDate) { this.slotDate = slotDate; }

    public String getMealType() { return mealType; }
    public void setMealType(String mealType) { this.mealType = mealType; }

    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }

    public Double getServings() { return servings; }
    public void setServings(Double servings) { this.servings = servings; }

    public Double getEstimatedCalories() { return estimatedCalories; }
    public void setEstimatedCalories(Double estimatedCalories) { this.estimatedCalories = estimatedCalories; }

    public Double getEstimatedProtein() { return estimatedProtein; }
    public void setEstimatedProtein(Double estimatedProtein) { this.estimatedProtein = estimatedProtein; }

    public Double getEstimatedFiber() { return estimatedFiber; }
    public void setEstimatedFiber(Double estimatedFiber) { this.estimatedFiber = estimatedFiber; }

    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }

    public Boolean getCostComplete() { return costComplete; }
    public void setCostComplete(Boolean costComplete) { this.costComplete = costComplete; }

    public Boolean getNutritionComplete() { return nutritionComplete; }
    public void setNutritionComplete(Boolean nutritionComplete) { this.nutritionComplete = nutritionComplete; }
}
