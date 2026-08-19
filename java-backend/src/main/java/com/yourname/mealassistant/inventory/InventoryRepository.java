package com.yourname.mealassistant.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    public List<InventoryItem> findByNameContainingIgnoreCase(String name);
}