package com.yourname.mealassistant.pricing;

import com.yourname.mealassistant.common.dto.ApiResponse;
import com.yourname.mealassistant.pricing.dto.AddPriceRequest;
import com.yourname.mealassistant.pricing.dto.PriceTagPhotoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// ApiResponse<T> wrapping. The from-photo endpoint mirrors ReceiptController's
// multipart @RequestParam("file") style, but is synchronous ( that decision).
@RestController
@RequestMapping("/api/prices")
public class PricingController {

    private final IngredientPriceService service;

    public PricingController(IngredientPriceService service) {
        this.service = service;
    }

    // DECISION: is a "list all known prices" endpoint in scope (for a prices screen
    //   where the user reviews/edits the catalog)? Sketched; drop if not needed this phase.
    @GetMapping("")
    public ResponseEntity<ApiResponse<List<IngredientPrice>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.findAll()));
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<IngredientPrice>> add(@RequestBody AddPriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.addManualPrice(request)));
    }

    // DECISION: does this auto-save, or return the parsed values for the user to
    //   confirm and then POST to the endpoint above? See IngredientPriceService.addFromPhoto.
    @PostMapping("/from-photo")
    public ResponseEntity<ApiResponse<PriceTagPhotoResponse>> fromPhoto(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(service.addFromPhoto(file.getBytes(), file.getContentType())));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("Failed to read file: " + e.getMessage()));
        }
    }
}
