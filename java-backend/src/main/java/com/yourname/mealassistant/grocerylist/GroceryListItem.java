package com.yourname.mealassistant.grocerylist;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// One line on a persisted, generated grocery list: an ingredient the user still needs
// to buy for a chosen meal plan, after what's already in inventory has been subtracted.
//
// Decided: grouped by mealPlanId (raw FK Long -> meal_plan.id, matching
//   RecipeIngredient's style). One generated list == all rows for a given mealPlanId.
// Decided: when a plan's slot is later swapped, the existing list for that
//   mealPlanId is marked stale (this flag) — the user must regenerate; it is not auto-rebuilt.
// Decided: `purchased` lets the UI check items off while shopping.
//
// Decided: the model stays flat — stale/generatedAt live on each item, there is no
//   GroceryList header entity. One generated list == all rows for a mealPlanId. Promote to a
//   GroceryList + GroceryListItem pair only if a list ever needs its own metadata (name, a
//   single stored total, one generatedAt).
// Decided: regenerating for a mealPlanId is DELETE-then-insert (not mark-stale) — see
//   GroceryListService.generateGroceryList. `stale` is set only by a later slot swap, never by
//   regenerate.
@Entity
@Table(name = "grocery_list_item")
public class GroceryListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meal_plan_id")
    private Long mealPlanId;

    @Column(name = "item_name")
    private String itemName;

    // Decided: the grocery list uses name-match-only subtraction (ingredient on hand
    //   in ANY quantity -> not on the list), so quantity here is the recipe-side required amount,
    //   NOT a reconciled "buy exactly this much". Type Double to match RecipeIngredient.quantity
    //   / DOUBLE PRECISION. unit is the display-only label carried from recipe_ingredients.
    private Double quantity;

    private String unit;

    // Money -> BigDecimal / NUMERIC. Nullable: null when the ingredient had no ingredient_price
    // row (the list-level costIncomplete flag on the response then covers it).
    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(name = "purchased")
    private Boolean purchased = false;

    @Column(name = "stale")
    private Boolean stale = false;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt = LocalDateTime.now();

    public GroceryListItem() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMealPlanId() { return mealPlanId; }
    public void setMealPlanId(Long mealPlanId) { this.mealPlanId = mealPlanId; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public Double getQuantity() { return quantity; }
    public void setQuantity(Double quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }

    public Boolean getPurchased() { return purchased; }
    public void setPurchased(Boolean purchased) { this.purchased = purchased; }

    public Boolean getStale() { return stale; }
    public void setStale(Boolean stale) { this.stale = stale; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
}
