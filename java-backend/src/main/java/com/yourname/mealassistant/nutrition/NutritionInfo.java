package com.yourname.mealassistant.nutrition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "nutrition_info")
public class NutritionInfo {
    @Id
    @Column(name = "item_name")
    private String itemName;

    @Column(name = "base_quantity")
    private Double baseQuantity;

    private Double calories;
    private Double protein;
    private Double fibers;
    private Double fats;
    private Double carbs;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public NutritionInfo() {}

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public Double getBaseQuantity() { return baseQuantity; }
    public void setBaseQuantity(Double baseQuantity) { this.baseQuantity = baseQuantity; }

    public Double getCalories() { return calories; }
    public void setCalories(Double calories) { this.calories = calories; }

    public Double getProtein() { return protein; }
    public void setProtein(Double protein) { this.protein = protein; }

    public Double getFibers() { return fibers; }
    public void setFibers(Double fibers) { this.fibers = fibers; }

    public Double getFats() { return fats; }
    public void setFats(Double fats) { this.fats = fats; }

    public Double getCarbs() { return carbs; }
    public void setCarbs(Double carbs) { this.carbs = carbs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
