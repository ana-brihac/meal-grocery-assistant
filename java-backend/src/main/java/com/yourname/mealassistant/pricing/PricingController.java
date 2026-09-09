package com.yourname.mealassistant.pricing;

import com.yourname.mealassistant.common.exception.BadRequestException;
import com.yourname.mealassistant.pricing.dto.AddPriceRequest;
import com.yourname.mealassistant.pricing.dto.PriceTagPhotoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// Returns raw DTOs (the project-wide convention — see docs/backend-api.md). Errors come back as
// RFC 9457 ProblemDetail via GlobalExceptionHandler. The from-photo endpoint mirrors
// ReceiptController's multipart @RequestParam("file") style, but is synchronous.
@RestController
@RequestMapping("/api/prices")
public class PricingController {

    private final IngredientPriceService service;

    public PricingController(IngredientPriceService service) {
        this.service = service;
    }

    // Decided: keep the catalog-list endpoint — it backs a prices screen where the user
    //   reviews/edits the catalog (and supplies gramsPerItem for PER_ITEM rows; see below).
    @GetMapping("")
    public ResponseEntity<List<IngredientPrice>> listAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @PostMapping("")
    public ResponseEntity<IngredientPrice> add(@RequestBody AddPriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addManualPrice(request));
    }

    // Decided: this AUTO-SAVES (IngredientPriceService.addFromPhoto upserts immediately)
    //   and returns the stored values with saved=true plus the raw OCR text, so the client can
    //   show it for review and re-POST corrections to POST /api/prices above. An unreadable
    //   upload is a 400 before any OCR call.
    @PostMapping("/from-photo")
    public ResponseEntity<PriceTagPhotoResponse> fromPhoto(@RequestParam("file") MultipartFile file) {
        byte[] bytes;
        String contentType;
        try {
            bytes = file.getBytes();
            contentType = file.getContentType();
        } catch (Exception e) {
            throw new BadRequestException("Failed to read file: " + e.getMessage(), e);
        }
        return ResponseEntity.ok(service.addFromPhoto(bytes, contentType));
    }
}
