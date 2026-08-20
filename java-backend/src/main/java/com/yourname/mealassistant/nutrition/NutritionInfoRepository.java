package com.yourname.mealassistant.nutrition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NutritionInfoRepository extends JpaRepository<NutritionInfo, String> {
}
