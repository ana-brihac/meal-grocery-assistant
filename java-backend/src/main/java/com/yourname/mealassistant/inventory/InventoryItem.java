package com.yourname.mealassistant.inventory;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.math.BigDecimal;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TODO: fields — name (String), quantity (Integer), expiryDate (LocalDate, nullable)
    private String name;
    private BigDecimal quantity;
    private LocalDate expiryDate;
    // Check db/init/001_init_schema.sql — your columns here MUST match that schema
    
    // if ddl-auto=validate, or Hibernate will silently diverge from it if =update.
    
    // Go check your init script now and make sure column names line up.

    protected InventoryItem() {} // JPA needs this

    // TODO: real constructor + getters/setters
    public InventoryItem(String name, BigDecimal quantity) {
        this.name = name;
        this.quantity = quantity;
    }

    public Long getId() { 
        return id; 
    }

    public void setId(Long id) { 
        this.id = id; 
    }

    public String getName() { 
        return name; 
    }

    public void setName(String name) {
        this.name = name; 
        }

    public BigDecimal getQuantity() { 
        return quantity; 
    }

    public void setQuantity(BigDecimal quantity) { 
        this.quantity = quantity; 
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }
}