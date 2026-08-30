package com.yourname.mealassistant.mealplan.dto;

import java.time.LocalDate;
import java.util.List;

// What to generate. A record, like RecipeSearchRequest.
// Decided: default layout is BREAKFAST / LUNCH / DINNER x 7 days starting
//   weekStartDate. mealTypes / days let the client override that; servingsPerMeal scales
//   nutrition + cost.
// DECISION: remaining field questions —
//   - are `mealTypes` and `days` truly client-overridable, or fixed for now with this record
//     only carrying weekStartDate + servingsPerMeal?
//   - nullable-with-defaults here, or required and defaulted in the controller/service?
//   - does the request ever carry target overrides, or always the stored UserPreference?
//     (Decided elsewhere: always the stored preference, snapshot onto the plan.)
public record MealPlanRequest(
        LocalDate weekStartDate,
        Integer days,
        List<String> mealTypes,
        Double servingsPerMeal) {
}
