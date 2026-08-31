package com.yourname.mealassistant.mealplan.dto;

import java.time.LocalDate;
import java.util.List;

// What to generate. A record, like RecipeSearchRequest.
// Decided: default layout is BREAKFAST / LUNCH / DINNER x 7 days starting
//   weekStartDate. mealTypes / days let the client override that; servingsPerMeal scales
//   nutrition + cost.
// Decided:
//   - `days`, `mealTypes`, `servingsPerMeal` ARE client-overridable; all three are optional.
//   - weekStartDate is required; the rest are nullable here and defaulted in MealPlanService
//     (7 days / BREAKFAST,LUNCH,DINNER / 1.0 serving).
//   - the request NEVER carries calorie/protein/fiber/budget overrides — those always come from
//     the stored UserPreference and are snapshot onto the plan.
public record MealPlanRequest(
        LocalDate weekStartDate,
        Integer days,
        List<String> mealTypes,
        Double servingsPerMeal) {
}
