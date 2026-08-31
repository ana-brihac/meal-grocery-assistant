package com.yourname.mealassistant.grocerylist;

import com.yourname.mealassistant.common.dto.MissingIngredientPrice;
import com.yourname.mealassistant.common.util.ItemNameNormalizer;
import com.yourname.mealassistant.grocerylist.dto.GroceryListRequest;
import com.yourname.mealassistant.grocerylist.dto.GroceryListResponse;
import com.yourname.mealassistant.inventory.InventoryRepository;
import com.yourname.mealassistant.mealplan.MealPlanSlot;
import com.yourname.mealassistant.mealplan.MealPlanSlotRepository;
import com.yourname.mealassistant.pricing.IngredientPriceService;
import com.yourname.mealassistant.recipe.RecipeIngredient;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// Turns a chosen meal plan into a shopping list: aggregate every ingredient across the
// plan's slots, subtract what's already in inventory, price the remainder, persist and return.
//
// Decided: input is a mealPlanId (plans are persisted). Regenerating deletes the
//   previous rows for that plan and inserts a fresh set (so `purchased` ticks from a prior run
//   are not carried over — a known, accepted limitation for now).
// Decided: inventory subtraction is NAME-MATCH ONLY. If an ingredient's normalized
//   name is on hand in ANY quantity it's dropped from the list entirely; amounts/units are not
//   reconciled (inventory_items has no unit column and the app has no unit conversion). This
//   deliberately over-subtracts.
// Decided: names are matched via ItemNameNormalizer (same key space as
//   nutrition_info / ingredient_price).
// Decided: an ingredient with no ingredient_price row still appears, with a null
//   estimatedCost; the response's costIncomplete flag signals the total is a lower bound, and
//   the ingredient is named in the response's missingPrices list (with a reason) so the user
//   can be prompted to add the price.
@Service
public class GroceryListService {

    private final RecipeIngredientRepository recipeIngredientRepository;
    private final InventoryRepository inventoryRepository;
    private final GroceryListRepository groceryListRepository;
    private final MealPlanSlotRepository mealPlanSlotRepository;
    private final IngredientPriceService ingredientPriceService;

    public GroceryListService(RecipeIngredientRepository recipeIngredientRepository,
                              InventoryRepository inventoryRepository,
                              GroceryListRepository groceryListRepository,
                              MealPlanSlotRepository mealPlanSlotRepository,
                              IngredientPriceService ingredientPriceService) {
        this.recipeIngredientRepository = recipeIngredientRepository;
        this.inventoryRepository = inventoryRepository;
        this.groceryListRepository = groceryListRepository;
        this.mealPlanSlotRepository = mealPlanSlotRepository;
        this.ingredientPriceService = ingredientPriceService;
    }

    public GroceryListResponse generateGroceryList(GroceryListRequest request) {
        if (request == null || request.mealPlanId() == null) {
            throw new IllegalArgumentException("mealPlanId is required");
        }
        Long mealPlanId = request.mealPlanId();
        List<MealPlanSlot> slots = mealPlanSlotRepository.findByMealPlanId(mealPlanId);

        // 1. aggregate required grams per normalized ingredient name across every slot
        Map<String, Aggregate> byIngredient = new LinkedHashMap<>();
        for (MealPlanSlot slot : slots) {
            double servings = slot.getServings() == null ? 1.0 : slot.getServings();
            for (RecipeIngredient ingredient : recipeIngredientRepository.findByRecipeId(slot.getRecipeId())) {
                String key = ItemNameNormalizer.normalize(ingredient.getIngredientName());
                double grams = (ingredient.getQuantity() == null ? 0.0 : ingredient.getQuantity()) * servings;
                byIngredient.computeIfAbsent(key, k -> new Aggregate(k, ingredient.getUnit())).grams += grams;
            }
        }

        // 2. drop anything already on hand (name-match only)
        Set<String> onHand = inventoryRepository.findAll().stream()
                .map(item -> ItemNameNormalizer.normalize(item.getName()))
                .collect(Collectors.toSet());

        // 3. replace the previous list for this plan (deleteAll(Iterable) is transactional in
        //    SimpleJpaRepository; a derived deleteBy... would need its own @Transactional)
        groceryListRepository.deleteAll(groceryListRepository.findByMealPlanId(mealPlanId));

        List<GroceryListItem> toSave = new ArrayList<>();
        for (Aggregate agg : byIngredient.values()) {
            if (onHand.contains(agg.key)) continue;

            Optional<BigDecimal> cost = ingredientPriceService.estimateIngredientCost(agg.key, agg.grams);

            GroceryListItem item = new GroceryListItem();
            item.setMealPlanId(mealPlanId);
            item.setItemName(agg.key);
            item.setQuantity(agg.grams);
            item.setUnit(agg.unit);
            item.setEstimatedCost(cost.orElse(null));
            item.setPurchased(false);
            item.setStale(false);
            toSave.add(item);
        }

        return toResponse(mealPlanId, groceryListRepository.saveAll(toSave));
    }

    // Fetch the current list for a plan. `stale` on the response is true if any slot was swapped
    // after this list was generated (see MealPlanService.replaceSlot).
    public GroceryListResponse getGroceryList(Long mealPlanId) {
        return toResponse(mealPlanId, groceryListRepository.findByMealPlanId(mealPlanId));
    }

    public GroceryListResponse setPurchased(Long itemId, boolean purchased) {
        GroceryListItem item = groceryListRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Grocery list item not found: " + itemId));
        item.setPurchased(purchased);
        groceryListRepository.save(item);
        return toResponse(item.getMealPlanId(), groceryListRepository.findByMealPlanId(item.getMealPlanId()));
    }

    private GroceryListResponse toResponse(Long mealPlanId, List<GroceryListItem> items) {
        List<GroceryListResponse.Item> dtos = items.stream()
                .map(i -> new GroceryListResponse.Item(i.getId(), i.getItemName(), i.getQuantity(),
                        i.getUnit(), i.getEstimatedCost(), Boolean.TRUE.equals(i.getPurchased())))
                .toList();

        BigDecimal total = items.stream()
                .map(GroceryListItem::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean costIncomplete = items.stream().anyMatch(i -> i.getEstimatedCost() == null);
        boolean stale = items.stream().anyMatch(i -> Boolean.TRUE.equals(i.getStale()));

        // Notify the user which ingredients still need a price so they can add it (or a
        // gramsPerItem) via POST /api/prices. Reclassified fresh each call, so it clears once
        // the price exists and the list is refetched.
        List<MissingIngredientPrice> missingPrices = new ArrayList<>();
        for (GroceryListItem i : items) {
            if (i.getEstimatedCost() != null) continue;
            double grams = i.getQuantity() == null ? 0.0 : i.getQuantity();
            IngredientPriceService.PriceGap gap = ingredientPriceService.estimateCost(i.getItemName(), grams).gap();
            if (gap != IngredientPriceService.PriceGap.NONE) {
                missingPrices.add(new MissingIngredientPrice(i.getItemName(), reasonFor(gap)));
            }
        }

        return new GroceryListResponse(mealPlanId, dtos, total, costIncomplete, stale, missingPrices);
    }

    private static String reasonFor(IngredientPriceService.PriceGap gap) {
        return gap == IngredientPriceService.PriceGap.NEEDS_GRAMS_PER_ITEM
                ? MissingIngredientPrice.REASON_NEEDS_GRAMS_PER_ITEM
                : MissingIngredientPrice.REASON_NO_PRICE_ON_FILE;
    }

    // Mutable accumulator for step 1.
    private static final class Aggregate {
        final String key;
        final String unit;
        double grams;

        Aggregate(String key, String unit) {
            this.key = key;
            this.unit = unit;
        }
    }
}
