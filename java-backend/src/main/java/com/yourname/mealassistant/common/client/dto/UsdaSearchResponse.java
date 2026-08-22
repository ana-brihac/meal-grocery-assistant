package com.yourname.mealassistant.common.client.dto;

import java.util.List;

public class UsdaSearchResponse {
    
    private List<UsdaFood> foods;

    public List<UsdaFood> getFoods() { return foods; }
    public void setFoods(List<UsdaFood> foods) { this.foods = foods; }

    public static class UsdaFood {
        private String description;
        private List<UsdaNutrient> foodNutrients;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public List<UsdaNutrient> getFoodNutrients() { return foodNutrients; }
        public void setFoodNutrients(List<UsdaNutrient> foodNutrients) { this.foodNutrients = foodNutrients; }
    }

    public static class UsdaNutrient {
        private String nutrientName;
        private Double value;
        private String unitName;

        public String getNutrientName() { return nutrientName; }
        public void setNutrientName(String nutrientName) { this.nutrientName = nutrientName; }
        
        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
        
        public String getUnitName() { return unitName; }
        public void setUnitName(String unitName) { this.unitName = unitName; }
    }
}
