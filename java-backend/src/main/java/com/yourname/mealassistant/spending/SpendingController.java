package com.yourname.mealassistant.spending;

import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/spending")
public class SpendingController {
    
    private final SpendingService service;
    
    public SpendingController(SpendingService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<SpendingSummaryResponse> getSummary(@RequestParam Long userId) {
        return ResponseEntity.ok(service.getSpendingSummary(userId));
    }
}
