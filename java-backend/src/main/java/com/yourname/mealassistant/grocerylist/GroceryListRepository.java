package com.yourname.mealassistant.grocerylist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroceryListRepository extends JpaRepository<GroceryListItem, Long> {

    // All lines of the list generated for one meal plan. Used to render the list, to mark it
    // stale after a slot swap (MealPlanService.replaceSlot), and — via deleteAll(...) — to clear
    // the previous set on regenerate.
    List<GroceryListItem> findByMealPlanId(Long mealPlanId);
}
