package com.yourname.mealassistant.common.exception;

import com.yourname.mealassistant.mealplan.MealPlanController;
import com.yourname.mealassistant.mealplan.MealPlanService;
import com.yourname.mealassistant.nutrition.NutritionController;
import com.yourname.mealassistant.nutrition.NutritionService;
import com.yourname.mealassistant.pricing.IngredientPriceService;
import com.yourname.mealassistant.pricing.PricingController;
import com.yourname.mealassistant.receipt.ReceiptController;
import com.yourname.mealassistant.receipt.ReceiptService;
import com.yourname.mealassistant.recipe.RecipeController;
import com.yourname.mealassistant.recipe.RecipeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The only test in this module that runs the real Spring MVC layer (@WebMvcTest slice, so
// Boot's Jackson + the @RestControllerAdvice are wired). It verifies the ACTUAL HTTP status
// codes and the application/problem+json body that GlobalExceptionHandler produces — the
// direct-invocation controller tests can only see the raw exception, not this mapping.
@WebMvcTest(controllers = {MealPlanController.class, PricingController.class, NutritionController.class,
        RecipeController.class, ReceiptController.class})
class ApiErrorMappingTest {

    @Autowired MockMvc mockMvc;

    @MockBean MealPlanService mealPlanService;
    @MockBean IngredientPriceService ingredientPriceService;
    @MockBean NutritionService nutritionService;
    @MockBean RecipeService recipeService;
    @MockBean ReceiptService receiptService;

    @Test
    void notFoundException_maps_to_404_problemDetail() throws Exception {
        when(mealPlanService.getPlan(999L)).thenThrow(new NotFoundException("Meal plan not found: 999"));

        mockMvc.perform(get("/api/mealplan/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Meal plan not found: 999"));
    }

    @Test
    void badRequestException_maps_to_400_problemDetail() throws Exception {
        when(ingredientPriceService.addManualPrice(any()))
                .thenThrow(new BadRequestException("pricingMode must be PER_ITEM or PER_KG, got: per_dozen"));

        mockMvc.perform(post("/api/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemName\":\"egg\",\"price\":0.30,\"pricingMode\":\"per_dozen\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void nutritionApiException_maps_to_502_problemDetail() throws Exception {
        when(nutritionService.getSummary(any(), any(), any()))
                .thenThrow(new NutritionApiException("USDA API Rate Limit Exceeded"));

        mockMvc.perform(get("/api/nutrition/summary")
                        .param("userId", "1")
                        .param("from", "2026-08-01T00:00:00")
                        .param("to", "2026-08-31T23:59:59"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void uncaughtException_maps_to_500_problemDetail() throws Exception {
        when(recipeService.getRecipe(5L)).thenThrow(new RuntimeException("kaboom"));

        mockMvc.perform(get("/api/recipes/5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    void receiptUpload_success_is_202_accepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "r.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/receipts/upload").file(file))
                .andExpect(status().isAccepted());
    }
}
