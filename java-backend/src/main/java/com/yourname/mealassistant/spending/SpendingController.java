package com.yourname.mealassistant.spending;

import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/spending")
public class SpendingController {
    
    private final SpendingService service;
    
    public SpendingController(SpendingService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<SpendingSummaryResponse> getSummary(
            @RequestParam Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getSpendingSummary(userId, from, to));
    }
}
