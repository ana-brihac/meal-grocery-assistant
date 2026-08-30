package com.yourname.mealassistant.mealplan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MealPlanSlotRepository extends JpaRepository<MealPlanSlot, Long> {

    List<MealPlanSlot> findByMealPlanId(Long mealPlanId);
}
