package com.yourname.mealassistant.dashboard;

import com.yourname.mealassistant.dashboard.dto.DashboardSummaryResponse;
import com.yourname.mealassistant.nutrition.NutritionService;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.spending.SpendingService;
import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

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
    public ResponseEntity<DashboardSummaryResponse> getDashboardSummary(
            @RequestParam Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        NutritionSummaryResponse nutrition = nutritionService.getSummary(userId, from.atStartOfDay(), to.atTime(23, 59, 59));
        SpendingSummaryResponse spending = spendingService.getSpendingSummary(userId, from, to);
        
        DashboardSummaryResponse combinedData = new DashboardSummaryResponse(nutrition, spending);
        
        return ResponseEntity.ok(combinedData);
    }
}
