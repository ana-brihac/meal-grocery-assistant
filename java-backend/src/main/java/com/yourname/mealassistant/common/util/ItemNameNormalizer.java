package com.yourname.mealassistant.common.util;

public class ItemNameNormalizer {
    
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        } 

        input = input.toLowerCase().trim();

        input = input.replaceAll("\\b\\d+(\\.\\d+)?\\s*(g|kg|ml|l|oz|lb|lbs)\\b", "");
        
        return input.replaceAll("\\s+", " ").trim();
    }
}
