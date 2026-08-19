package com.yourname.mealassistant.inventory;

import com.yourname.mealassistant.common.client.MlServiceClient;
import org.springframework.stereotype.Service;
import java.util.List;
import java.math.BigDecimal;

@Service
public class InventoryService {

    private final InventoryRepository repository;
    private final MlServiceClient mlServiceClient;

    public InventoryService(InventoryRepository repository, MlServiceClient mlServiceClient) {
        this.repository = repository;
        this.mlServiceClient = mlServiceClient;
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

    public String pingDummyEndpoint() {
        return mlServiceClient.pingDummyEndpoint();
    }
}