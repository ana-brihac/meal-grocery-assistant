package com.yourname.mealassistant.inventory;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.yourname.mealassistant.receipt.Receipt;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private Receipt receipt;

    private String name;
    private BigDecimal quantity;
    private LocalDate expiryDate;
    private BigDecimal price;

    protected InventoryItem() {}

    public InventoryItem(String name, BigDecimal quantity, BigDecimal price) {
        this.name = name;
        this.quantity = quantity;
        this.price = price;
    }

    public Long getId() { 
        return id; 
    }

    public void setId(Long id) { 
        this.id = id; 
    }

    public Receipt getReceipt() {
        return receipt;
    }

    public void setReceipt(Receipt receipt) {
        this.receipt = receipt;
    }

    public BigDecimal getPrice() { 
        return price; 
    }

    public void setPrice(BigDecimal price) { 
        this.price = price; 
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