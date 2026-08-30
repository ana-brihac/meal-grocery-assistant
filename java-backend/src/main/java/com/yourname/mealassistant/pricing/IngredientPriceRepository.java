package com.yourname.mealassistant.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IngredientPriceRepository extends JpaRepository<IngredientPrice, Long> {

    // Key is the normalized item name (see IngredientPrice). Used by the upsert path and by the
    // meal-plan / grocery-list cost lookups.
    Optional<IngredientPrice> findByItemName(String itemName);
}
