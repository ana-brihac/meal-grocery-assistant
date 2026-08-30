package com.yourname.mealassistant.grocerylist;

import com.yourname.mealassistant.common.dto.ApiResponse;
import com.yourname.mealassistant.grocerylist.dto.GroceryListRequest;
import com.yourname.mealassistant.grocerylist.dto.GroceryListResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ApiResponse<T> wrapping, matching MealPlanController.
@RestController
@RequestMapping("/api/grocerylist")
public class GroceryListController {

    private final GroceryListService service;

    public GroceryListController(GroceryListService service) {
        this.service = service;
    }

    // Generate (or regenerate) the list for a meal plan.
    // DECISION: regenerate = replace vs. append — see GroceryListService.
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<GroceryListResponse>> generate(@RequestBody GroceryListRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.generateGroceryList(request)));
    }

    // Fetch the current list for a plan (response carries `stale` if the plan changed since).
    @GetMapping("/{mealPlanId}")
    public ResponseEntity<ApiResponse<GroceryListResponse>> get(@PathVariable Long mealPlanId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getGroceryList(mealPlanId)));
    }

    // Check an item off (or back on) while shopping.
    // DECISION: PATCH vs PUT; body shape ({purchased: true}) vs a query param.
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<GroceryListResponse>> setPurchased(@PathVariable Long itemId,
                                                                        @RequestParam boolean purchased) {
        return ResponseEntity.ok(ApiResponse.ok(service.setPurchased(itemId, purchased)));
    }
}
