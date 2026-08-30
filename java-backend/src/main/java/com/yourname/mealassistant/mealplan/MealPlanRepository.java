package com.yourname.mealassistant.mealplan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MealPlanRepository extends JpaRepository<MealPlan, Long> {

    // History view — newest first. DECISION: does history need pagination? Left
    //   unbounded for now, matching GET /api/inventory (also unbounded — see docs/known-issues).
    List<MealPlan> findAllByOrderByCreatedAtDesc();

    // Used by selectForWeek to clear any existing SELECTED plan for the target week before
    // marking the newly cloned one.
    List<MealPlan> findByWeekStartDateAndStatus(LocalDate weekStartDate, String status);
}
