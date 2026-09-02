package com.yourname.mealassistant.grocerylist;

import com.yourname.mealassistant.grocerylist.dto.GroceryListRequest;
import com.yourname.mealassistant.grocerylist.dto.GroceryListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Returns raw DTOs (the project-wide convention — see docs/backend-api.md). Errors come back as
// RFC 9457 ProblemDetail via GlobalExceptionHandler.
@RestController
@RequestMapping("/api/grocerylist")
public class GroceryListController {

    private final GroceryListService service;

    public GroceryListController(GroceryListService service) {
        this.service = service;
    }

    // Generate (or regenerate) the list for a meal plan.
    // Decided: regenerate REPLACES (delete-then-insert per mealPlanId), never appends —
    //   see GroceryListService.generateGroceryList.
    @PostMapping("/generate")
    public ResponseEntity<GroceryListResponse> generate(@RequestBody GroceryListRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generateGroceryList(request));
    }

    // Fetch the current list for a plan (response carries `stale` if the plan changed since).
    @GetMapping("/{mealPlanId}")
    public ResponseEntity<GroceryListResponse> get(@PathVariable Long mealPlanId) {
        return ResponseEntity.ok(service.getGroceryList(mealPlanId));
    }

    // Check an item off (or back on) while shopping.
    // Decided: PATCH (single-field partial update) with `purchased` as a query param —
    //   matches the low-ceremony style elsewhere; no request body.
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<GroceryListResponse> setPurchased(@PathVariable Long itemId,
                                                            @RequestParam boolean purchased) {
        return ResponseEntity.ok(service.setPurchased(itemId, purchased));
    }
}
