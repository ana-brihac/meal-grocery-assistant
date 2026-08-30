package com.yourname.mealassistant.mealplan.dto;

import java.time.LocalDate;

// Body for POST /api/mealplan/{planId}/select — "use this historical plan for the week starting
// weekStartDate". Decided: this clones the source plan into a new MealPlan row for
// that week; see MealPlanService.selectForWeek.
// DECISION: anything beyond weekStartDate? e.g. a flag to re-run the optimizer's swap
//   pass if the copied slots fall out of band against current targets (vs. copy verbatim and
//   just flag).
public record SelectPlanRequest(LocalDate weekStartDate) {
}
