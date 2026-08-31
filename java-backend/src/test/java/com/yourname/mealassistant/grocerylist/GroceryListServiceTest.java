package com.yourname.mealassistant.grocerylist;

import com.yourname.mealassistant.grocerylist.dto.GroceryListRequest;
import com.yourname.mealassistant.grocerylist.dto.GroceryListResponse;
import com.yourname.mealassistant.inventory.InventoryItem;
import com.yourname.mealassistant.inventory.InventoryRepository;
import com.yourname.mealassistant.mealplan.MealPlanSlot;
import com.yourname.mealassistant.mealplan.MealPlanSlotRepository;
import com.yourname.mealassistant.pricing.IngredientPriceService;
import com.yourname.mealassistant.recipe.RecipeIngredient;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroceryListServiceTest {

    @Mock RecipeIngredientRepository recipeIngredientRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock GroceryListRepository groceryListRepository;
    @Mock MealPlanSlotRepository mealPlanSlotRepository;
    @Mock IngredientPriceService ingredientPriceService;

    @InjectMocks GroceryListService service;

    private static MealPlanSlot slot(long recipeId, double servings) {
        MealPlanSlot s = new MealPlanSlot();
        s.setRecipeId(recipeId);
        s.setServings(servings);
        return s;
    }

    private static RecipeIngredient ing(String name, double qty, String unit) {
        RecipeIngredient ri = new RecipeIngredient();
        ri.setIngredientName(name);
        ri.setQuantity(qty);
        ri.setUnit(unit);
        return ri;
    }

    private static InventoryItem inv(String name) {
        return new InventoryItem(name, BigDecimal.ONE, BigDecimal.ZERO);
    }

    private static IngredientPriceService.CostEstimate noCost(IngredientPriceService.PriceGap gap) {
        return new IngredientPriceService.CostEstimate(Optional.empty(), gap);
    }

    @Test
    void generate_aggregatesAcrossSlots_dropsOnHand_pricesRemainder() {
        when(mealPlanSlotRepository.findByMealPlanId(7L)).thenReturn(List.of(slot(1L, 1.0), slot(2L, 2.0)));
        when(recipeIngredientRepository.findByRecipeId(1L))
                .thenReturn(List.of(ing("Flour", 100.0, "g"), ing("Salt", 5.0, "g")));
        when(recipeIngredientRepository.findByRecipeId(2L)).thenReturn(List.of(ing("flour", 50.0, "g")));
        when(inventoryRepository.findAll()).thenReturn(List.of(inv("Salt")));
        when(groceryListRepository.findByMealPlanId(7L)).thenReturn(List.of());
        when(ingredientPriceService.estimateIngredientCost(eq("flour"), anyDouble()))
                .thenReturn(Optional.of(new BigDecimal("1.20")));
        when(groceryListRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        GroceryListResponse res = service.generateGroceryList(new GroceryListRequest(7L));

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).itemName()).isEqualTo("flour");
        assertThat(res.items().get(0).quantity()).isEqualTo(200.0);          // 100*1 + 50*2
        assertThat(res.items().get(0).estimatedCost()).isEqualByComparingTo("1.20");
        assertThat(res.costIncomplete()).isFalse();
        assertThat(res.missingPrices()).isEmpty();
        verify(groceryListRepository).deleteAll(anyIterable());
    }

    @Test
    void generate_missingPrice_keepsItemWithNullCostFlagsIncompleteAndNamesItInMissingPrices() {
        when(mealPlanSlotRepository.findByMealPlanId(7L)).thenReturn(List.of(slot(1L, 1.0)));
        when(recipeIngredientRepository.findByRecipeId(1L)).thenReturn(List.of(ing("saffron", 2.0, "g")));
        when(inventoryRepository.findAll()).thenReturn(List.of());
        when(groceryListRepository.findByMealPlanId(7L)).thenReturn(List.of());
        when(ingredientPriceService.estimateIngredientCost(eq("saffron"), anyDouble())).thenReturn(Optional.empty());
        when(ingredientPriceService.estimateCost(eq("saffron"), anyDouble()))
                .thenReturn(noCost(IngredientPriceService.PriceGap.NO_PRICE_ON_FILE));
        when(groceryListRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        GroceryListResponse res = service.generateGroceryList(new GroceryListRequest(7L));

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).estimatedCost()).isNull();
        assertThat(res.costIncomplete()).isTrue();
        assertThat(res.missingPrices()).hasSize(1);
        assertThat(res.missingPrices().get(0).ingredientName()).isEqualTo("saffron");
        assertThat(res.missingPrices().get(0).reason()).isEqualTo("NO_PRICE_ON_FILE");
    }

    @Test
    void generate_perItemPriceMissingGramsPerItem_reportsNeedsGramsPerItemReason() {
        when(mealPlanSlotRepository.findByMealPlanId(7L)).thenReturn(List.of(slot(1L, 1.0)));
        when(recipeIngredientRepository.findByRecipeId(1L)).thenReturn(List.of(ing("egg", 100.0, "g")));
        when(inventoryRepository.findAll()).thenReturn(List.of());
        when(groceryListRepository.findByMealPlanId(7L)).thenReturn(List.of());
        when(ingredientPriceService.estimateIngredientCost(eq("egg"), anyDouble())).thenReturn(Optional.empty());
        when(ingredientPriceService.estimateCost(eq("egg"), anyDouble()))
                .thenReturn(noCost(IngredientPriceService.PriceGap.NEEDS_GRAMS_PER_ITEM));
        when(groceryListRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        GroceryListResponse res = service.generateGroceryList(new GroceryListRequest(7L));

        assertThat(res.missingPrices()).hasSize(1);
        assertThat(res.missingPrices().get(0).ingredientName()).isEqualTo("egg");
        assertThat(res.missingPrices().get(0).reason()).isEqualTo("NEEDS_GRAMS_PER_ITEM");
    }

    @Test
    void generate_inventoryCoversEverything_returnsEmptyList() {
        when(mealPlanSlotRepository.findByMealPlanId(7L)).thenReturn(List.of(slot(1L, 1.0)));
        when(recipeIngredientRepository.findByRecipeId(1L)).thenReturn(List.of(ing("Rice", 100.0, "g")));
        when(inventoryRepository.findAll()).thenReturn(List.of(inv("rice")));
        when(groceryListRepository.findByMealPlanId(7L)).thenReturn(List.of());
        when(groceryListRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        GroceryListResponse res = service.generateGroceryList(new GroceryListRequest(7L));

        assertThat(res.items()).isEmpty();
        assertThat(res.costIncomplete()).isFalse();
        assertThat(res.missingPrices()).isEmpty();
    }

    @Test
    void setPurchased_updatesItemAndReturnsRefreshedList() {
        GroceryListItem item = new GroceryListItem();
        item.setId(99L);
        item.setMealPlanId(7L);
        item.setItemName("flour");
        item.setPurchased(false);

        when(groceryListRepository.findById(99L)).thenReturn(Optional.of(item));
        when(groceryListRepository.findByMealPlanId(7L)).thenReturn(List.of(item));
        when(ingredientPriceService.estimateCost(anyString(), anyDouble()))
                .thenReturn(noCost(IngredientPriceService.PriceGap.NO_PRICE_ON_FILE));

        GroceryListResponse res = service.setPurchased(99L, true);

        assertThat(item.getPurchased()).isTrue();
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).purchased()).isTrue();
        assertThat(res.missingPrices()).extracting("ingredientName").containsExactly("flour");
        verify(groceryListRepository).save(item);
    }

    @Test
    void getGroceryList_reportsStaleWhenAnyRowIsStale() {
        GroceryListItem fresh = new GroceryListItem();
        fresh.setMealPlanId(7L);
        fresh.setItemName("a");
        fresh.setStale(false);
        GroceryListItem stale = new GroceryListItem();
        stale.setMealPlanId(7L);
        stale.setItemName("b");
        stale.setStale(true);
        when(groceryListRepository.findByMealPlanId(7L)).thenReturn(List.of(fresh, stale));
        when(ingredientPriceService.estimateCost(anyString(), anyDouble()))
                .thenReturn(noCost(IngredientPriceService.PriceGap.NO_PRICE_ON_FILE));

        GroceryListResponse res = service.getGroceryList(7L);

        assertThat(res.stale()).isTrue();
    }
}
