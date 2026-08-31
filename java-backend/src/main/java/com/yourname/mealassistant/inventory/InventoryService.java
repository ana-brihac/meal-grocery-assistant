package com.yourname.mealassistant.inventory;

import org.springframework.stereotype.Service;
import java.util.List;
import java.math.BigDecimal;

@Service
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    public List<InventoryItem> getAllItems() {
        return repository.findAll();
    }

    public InventoryItem addItem(String name, BigDecimal quantity) {
        InventoryItem newItem = new InventoryItem();

        newItem.setName(name);
        newItem.setQuantity(quantity);

        return repository.save(newItem);
    }
}