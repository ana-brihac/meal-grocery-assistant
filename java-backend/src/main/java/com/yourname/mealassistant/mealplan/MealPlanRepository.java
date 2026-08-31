package com.yourname.mealassistant.mealplan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MealPlanRepository extends JpaRepository<MealPlan, Long> {

    // History view — newest first. Decided: unbounded, no pagination — plans accrue
    //   at roughly one per week, and this matches GET /api/inventory (also unbounded). Add
    //   paging only if a client actually needs it.
    List<MealPlan> findAllByOrderByCreatedAtDesc();

    // Used by selectForWeek to clear any existing SELECTED plan for the target week before
    // marking the newly cloned one.
    List<MealPlan> findByWeekStartDateAndStatus(LocalDate weekStartDate, String status);
}
