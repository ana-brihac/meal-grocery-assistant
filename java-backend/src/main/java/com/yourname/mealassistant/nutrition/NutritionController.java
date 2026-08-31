package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.dto.ApiResponse;
import com.yourname.mealassistant.nutrition.dto.DailyNutritionSummary;
import com.yourname.mealassistant.nutrition.dto.LogMealRequest;
import com.yourname.mealassistant.nutrition.dto.LogRecipeRequest;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/nutrition")
public class NutritionController {
    
    private final NutritionService service;

    public NutritionController(NutritionService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<NutritionSummaryResponse> getSummary(
            @RequestParam Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(service.getSummary(userId, from, to));
    }

    @PostMapping("/log")
    public ResponseEntity<Void> logMeal(@RequestBody LogMealRequest request) {
        service.logMeal(request.getUserId(), request.getItemName(), request.getQuantityGrams());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/log-recipe")
    public ResponseEntity<Void> logRecipe(@RequestBody LogRecipeRequest request) {
        service.logRecipe(request.getRecipeId(), request.getServings());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<List<DailyNutritionSummary>>> getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.ok(service.getDailyBreakdown(start, end)));
    }
}
