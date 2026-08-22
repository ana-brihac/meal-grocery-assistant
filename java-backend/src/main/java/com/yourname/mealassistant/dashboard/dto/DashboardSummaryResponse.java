package com.yourname.mealassistant.dashboard.dto;

import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;

public class DashboardSummaryResponse {
    private NutritionSummaryResponse nutrition;
    private SpendingSummaryResponse spending;

    public DashboardSummaryResponse() {}

    public DashboardSummaryResponse(NutritionSummaryResponse nutrition, SpendingSummaryResponse spending) {
        this.nutrition = nutrition;
        this.spending = spending;
    }

    public NutritionSummaryResponse getNutrition() { return nutrition; }
    public void setNutrition(NutritionSummaryResponse nutrition) { this.nutrition = nutrition; }

    public SpendingSummaryResponse getSpending() { return spending; }
    public void setSpending(SpendingSummaryResponse spending) { this.spending = spending; }
}
