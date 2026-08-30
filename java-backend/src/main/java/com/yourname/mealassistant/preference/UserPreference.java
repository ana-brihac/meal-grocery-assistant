package com.yourname.mealassistant.preference;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "user_preference")
public class UserPreference {

    public static final Long SINGLETON_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "daily_calorie_target")
    private Double dailyCalorieTarget;

    @Column(name = "daily_protein_target")
    private Double dailyProteinTarget;

    @Column(name = "daily_fiber_target")
    private Double dailyFiberTarget;

    @Column(name = "weekly_budget")
    private BigDecimal weeklyBudget;

    // Meal-prep: how many consecutive same-meal-type slots one cooked recipe may cover
    // in a generated plan. 1 = no batching (a distinct recipe per slot); 2-3 = "cook once, eat
    // 2-3 times". Applied to every plan; see MealPlanOptimizer.
    @Column(name = "meal_prep_batch_size")
    private Integer mealPrepBatchSize = 1;

    public UserPreference() {}

    public UserPreference(Double dailyCalorieTarget, Double dailyProteinTarget, Double dailyFiberTarget, BigDecimal weeklyBudget) {
        this.dailyCalorieTarget = dailyCalorieTarget;
        this.dailyProteinTarget = dailyProteinTarget;
        this.dailyFiberTarget = dailyFiberTarget;
        this.weeklyBudget = weeklyBudget;
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Double getCalories() {
        return this.dailyCalorieTarget;
    }

    public void setCalories(Double dailyCalorieTarget) {
        this.dailyCalorieTarget = dailyCalorieTarget;
    }

    public Double getProtein() {
        return this.dailyProteinTarget;
    }

    public void setProtein(Double dailyProteinTarget) {
        this.dailyProteinTarget = dailyProteinTarget;
    }

    public Double getFiber() {
        return this.dailyFiberTarget;
    }

    public void setFiber(Double dailyFiberTarget) {
        this.dailyFiberTarget = dailyFiberTarget;
    }

    public BigDecimal getWeeklyBudget() {
        return this.weeklyBudget;
    }

    public void setWeeklyBudget(BigDecimal weeklyBudget) {
        this.weeklyBudget = weeklyBudget;
    }

    public Integer getMealPrepBatchSize() {
        return this.mealPrepBatchSize;
    }

    public void setMealPrepBatchSize(Integer mealPrepBatchSize) {
        this.mealPrepBatchSize = mealPrepBatchSize;
    }
}
