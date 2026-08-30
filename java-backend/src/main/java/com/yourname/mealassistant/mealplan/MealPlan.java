package com.yourname.mealassistant.mealplan;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// A generated meal plan for one week. Persisted so the user can browse history and
// pick a past plan to reuse for an upcoming week.
//
// Decided: the plan is a header row plus one MealPlanSlot row per (date, meal type).
// Decided: the calorie/protein/fiber targets and the weekly budget the plan was built
//   against are SNAPSHOT onto this row at generation time. UserPreference is a single mutable
//   row (see preference/UserPreferenceService) — without a snapshot, old plans in history would
//   silently re-interpret against whatever the targets are today.
// Decided: "choose a plan from history for a week" is CLONE-on-select — see
//   MealPlanService.selectForWeek: a new MealPlan row is inserted for the target week with its
//   slots copied and the targets re-snapshot from current preferences; the original stays frozen.
//   sourcePlanId records where a clone came from.
// Decided: status is a plain String ("DRAFT" / "SELECTED"), matching the low-ceremony
//   String-not-enum style used elsewhere (e.g. RecipeSearchRequest.rankBy). At most one
//   "SELECTED" plan per weekStartDate — enforced in MealPlanService, not the schema.
//
// DECISION: does a plan need a userId? The rest of the app is single-tenant (no auth,
//   UserPreference is one row) — left off for now, consistent with RecipeController /
//   NutritionController#/calendar. Flag if multi-user ever lands.
@Entity
@Table(name = "meal_plan")
public class MealPlan {

    // status values (plain strings; the "one SELECTED per week" rule is enforced in
    // MealPlanService, not the schema).
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SELECTED = "SELECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start_date")
    private LocalDate weekStartDate;

    @Column(name = "status")
    private String status;

    // Nullable — set only on plans created by cloning a historical plan (selectForWeek).
    @Column(name = "source_plan_id")
    private Long sourcePlanId;

    // --- target snapshot (see class note) ---
    // Nutrition targets -> Double / DOUBLE PRECISION; budget -> BigDecimal / NUMERIC, per the
    // repo's type-pairing convention (docs/database.md).
    @Column(name = "calorie_target_snapshot")
    private Double calorieTargetSnapshot;

    @Column(name = "protein_target_snapshot")
    private Double proteinTargetSnapshot;

    @Column(name = "fiber_target_snapshot")
    private Double fiberTargetSnapshot;

    @Column(name = "weekly_budget_snapshot")
    private BigDecimal weeklyBudgetSnapshot;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public MealPlan() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getWeekStartDate() { return weekStartDate; }
    public void setWeekStartDate(LocalDate weekStartDate) { this.weekStartDate = weekStartDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getSourcePlanId() { return sourcePlanId; }
    public void setSourcePlanId(Long sourcePlanId) { this.sourcePlanId = sourcePlanId; }

    public Double getCalorieTargetSnapshot() { return calorieTargetSnapshot; }
    public void setCalorieTargetSnapshot(Double calorieTargetSnapshot) { this.calorieTargetSnapshot = calorieTargetSnapshot; }

    public Double getProteinTargetSnapshot() { return proteinTargetSnapshot; }
    public void setProteinTargetSnapshot(Double proteinTargetSnapshot) { this.proteinTargetSnapshot = proteinTargetSnapshot; }

    public Double getFiberTargetSnapshot() { return fiberTargetSnapshot; }
    public void setFiberTargetSnapshot(Double fiberTargetSnapshot) { this.fiberTargetSnapshot = fiberTargetSnapshot; }

    public BigDecimal getWeeklyBudgetSnapshot() { return weeklyBudgetSnapshot; }
    public void setWeeklyBudgetSnapshot(BigDecimal weeklyBudgetSnapshot) { this.weeklyBudgetSnapshot = weeklyBudgetSnapshot; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
