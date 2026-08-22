package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.client.NutritionApiClient;
import com.yourname.mealassistant.common.util.ItemNameNormalizer;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NutritionService {

    private final NutritionApiClient nutritionApiClient;
    private final NutritionInfoRepository nutritionInfoRepository;
    private final NutritionLogRepository nutritionLogRepository;

    public NutritionService(NutritionApiClient nutritionApiClient, 
                            NutritionInfoRepository nutritionInfoRepository,
                            NutritionLogRepository nutritionLogRepository) {
        this.nutritionApiClient = nutritionApiClient;
        this.nutritionInfoRepository = nutritionInfoRepository;
        this.nutritionLogRepository = nutritionLogRepository;
    }

    public NutritionInfo getOrFetchNutritionInfo(String foodName) {
        String normalized = ItemNameNormalizer.normalize(foodName);
        return nutritionInfoRepository.findById(normalized).orElseGet(() -> {
            
            var searchResponse = nutritionApiClient.searchFoodByName(normalized);
            
            NutritionInfo newInfo = new NutritionInfo();

            newInfo.setItemName(normalized);
            newInfo.setBaseQuantity(100.0);
            
            if (searchResponse != null && searchResponse.getFoods() != null && !searchResponse.getFoods().isEmpty()) {
                var firstFood = searchResponse.getFoods().get(0);
                
                if (firstFood.getFoodNutrients() != null) {
                    for (var nutrient : firstFood.getFoodNutrients()) {
                        String name = nutrient.getNutrientName();
                        if (name == null) continue;
                        
                        if (name.equalsIgnoreCase("Energy") || name.contains("Calories")) {
                            newInfo.setCalories(nutrient.getValue());
                        } else if (name.equalsIgnoreCase("Protein")) {
                            newInfo.setProtein(nutrient.getValue());
                        } else if (name.contains("Fiber")) {
                            newInfo.setFibers(nutrient.getValue());
                        } else if (name.contains("Total lipid (fat)") || name.equalsIgnoreCase("Fat")) {
                            newInfo.setFats(nutrient.getValue());
                        } else if (name.contains("Carbohydrate")) {
                            newInfo.setCarbs(nutrient.getValue());
                        }
                    }
                }
            }
            
            return nutritionInfoRepository.save(newInfo);
        });
    }

    public void logMeal(Long userId, String foodName, Double quantityGrams) {
        NutritionInfo info = getOrFetchNutritionInfo(foodName);

        NutritionLog logEntry = new NutritionLog();

        logEntry.setUserId(userId);
        logEntry.setItemName(foodName);
        logEntry.setQuantityGrams(quantityGrams);
        
        nutritionLogRepository.save(logEntry);
    }
    
    public NutritionSummaryResponse getSummary(Long userId, LocalDateTime from, LocalDateTime to) {
        var summary = new NutritionSummaryResponse();
        
        List<NutritionLog> logs = nutritionLogRepository.findByUserIdAndLoggedAtBetween(userId, from, to);

        for (NutritionLog log : logs) {
            nutritionInfoRepository.findById(log.getItemName()).ifPresent(info -> {
                double multiplier = log.getQuantityGrams() / info.getBaseQuantity();

                if (info.getCalories() != null) summary.addCalories(info.getCalories() * multiplier);
                if (info.getProtein() != null) summary.addProtein(info.getProtein() * multiplier);
                if (info.getCarbs() != null) summary.addCarbs(info.getCarbs() * multiplier);
                if (info.getFats() != null) summary.addFats(info.getFats() * multiplier);
                if (info.getFibers() != null) summary.addFibers(info.getFibers() * multiplier);
            });
        }
        
        return summary;
    }
}
