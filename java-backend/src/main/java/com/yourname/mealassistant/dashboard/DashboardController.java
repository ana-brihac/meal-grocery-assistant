package com.yourname.mealassistant.dashboard;

import com.yourname.mealassistant.dashboard.dto.DashboardSummaryResponse;
import com.yourname.mealassistant.nutrition.NutritionService;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.spending.SpendingService;
import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    
    private final NutritionService nutritionService;
    private final SpendingService spendingService;
    
    public DashboardController(NutritionService nutritionService, SpendingService spendingService) {
        this.nutritionService = nutritionService;
        this.spendingService = spendingService;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getDashboardSummary(@RequestParam Long userId) {
        NutritionSummaryResponse nutrition = nutritionService.getSummary(userId);
        SpendingSummaryResponse spending = spendingService.getSpendingSummary(userId);
        
        DashboardSummaryResponse combinedData = new DashboardSummaryResponse(nutrition, spending);
        
        return ResponseEntity.ok(combinedData);
    }
}
