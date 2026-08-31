package com.yourname.mealassistant.nutrition.dto;

import java.time.LocalDate;
import java.util.List;

public record DailyNutritionSummary(LocalDate date, Double totalCalories, Double totalProtein, Double totalFiber, List<LoggedMealEntry> entries) {

}
