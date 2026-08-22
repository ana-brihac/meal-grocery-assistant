package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.nutrition.dto.LogMealRequest;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/nutrition")
public class NutritionController {
    
    private final NutritionService service;

    public NutritionController(NutritionService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<NutritionSummaryResponse> getSummary(@RequestParam Long userId) {
        return ResponseEntity.ok(service.getSummary(userId));
    }

    @PostMapping("/log")
    public ResponseEntity<Void> logMeal(@RequestBody LogMealRequest request) {
        service.logMeal(request.getUserId(), request.getItemName(), request.getQuantityGrams());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
