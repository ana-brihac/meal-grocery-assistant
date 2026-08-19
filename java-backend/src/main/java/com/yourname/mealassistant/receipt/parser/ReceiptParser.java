package com.yourname.mealassistant.receipt.parser;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.math.BigDecimal;
import com.yourname.mealassistant.inventory.InventoryItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class ReceiptParser {
    
    public List<InventoryItem> parseReceiptText(String rawText) {
        System.out.println("====== GEMINI RAW OUTPUT ======");
        System.out.println(rawText);
        System.out.println("===============================");

        List<InventoryItem> items = new ArrayList<>();
        
        // Gemini often wraps JSON in markdown blocks
        String cleanJson = rawText.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").trim();
        
        // We don't wrap in an array anymore because Gemini might return an object like {"items": [...]}

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(cleanJson);
            
            // If Gemini returned an object with an "items" array, use that array
            JsonNode itemsArray = rootNode;
            if (rootNode.isObject() && rootNode.has("items")) {
                itemsArray = rootNode.get("items");
            }
            
            if (itemsArray.isArray()) {
                for (JsonNode node : itemsArray) {
                    // Check for "name" or "description"
                    String name = "Unknown Item";
                    if (node.has("name")) name = node.get("name").asText();
                    else if (node.has("description")) name = node.get("description").asText();
                    
                    BigDecimal quantity = node.has("quantity") ? new BigDecimal(node.get("quantity").asText()) : BigDecimal.ONE;
                    BigDecimal price = node.has("price") ? new BigDecimal(node.get("price").asText()) : BigDecimal.ZERO;
                    
                    items.add(new InventoryItem(name, quantity, price));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse Gemini JSON: " + e.getMessage());
        }

        System.out.println("Successfully parsed " + items.size() + " items.");
        return items;
    }
}
