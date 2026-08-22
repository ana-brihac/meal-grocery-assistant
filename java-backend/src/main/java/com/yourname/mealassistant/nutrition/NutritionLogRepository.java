package com.yourname.mealassistant.nutrition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NutritionLogRepository extends JpaRepository<NutritionLog, Long> {
	java.util.List<NutritionLog> findByUserId(Long userId);
}
