package com.yourname.mealassistant.inventory;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("")
    public ResponseEntity<List<InventoryItem>> listAll() {
        return ResponseEntity.ok(service.getAllItems());
    }

    @PostMapping("")
    public ResponseEntity<InventoryItem> addItem(@RequestBody ItemRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(service.addItem(request.getName(), request.getQuantity()));
    }

}
