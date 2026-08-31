package com.yourname.mealassistant.mealplan.dto;

import java.time.LocalDate;

// Body for POST /api/mealplan/{planId}/select — "use this historical plan for the week starting
// weekStartDate". Decided: this clones the source plan into a new MealPlan row for
// that week; see MealPlanService.selectForWeek.
// Decided: weekStartDate is the only field. The optimizer's per-day swap pass on
//   copied slots that fall out of band against current targets is ALWAYS run (see
//   MealPlanService.selectForWeek) — not an opt-in flag.
public record SelectPlanRequest(LocalDate weekStartDate) {
}
