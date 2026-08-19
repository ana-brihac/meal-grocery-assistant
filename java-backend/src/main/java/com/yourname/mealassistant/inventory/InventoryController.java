package com.yourname.mealassistant.inventory;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.yourname.mealassistant.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    // TODO: GET "" -> list all
    @GetMapping("")
    public ResponseEntity<ApiResponse<List<InventoryItem>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.getAllItems()));
    }
    // TODO: POST "" -> add item
    @PostMapping("")
    public ResponseEntity<ApiResponse<InventoryItem>> addItem(@RequestBody ItemRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.addItem(request.getName(), request.getQuantity())));
    }
    // TODO: GET "/ping-python" -> calls the ml-service, this is your Phase 1 deliverable endpoint
    @GetMapping("/ping-python")
    public ResponseEntity<ApiResponse<String>> callMl() {
        return ResponseEntity.ok(ApiResponse.ok(service.pingDummyEndpoint()));
    }

}