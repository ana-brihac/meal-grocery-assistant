package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.nutrition.dto.DailyNutritionSummary;
import com.yourname.mealassistant.nutrition.dto.LogMealRequest;
import com.yourname.mealassistant.nutrition.dto.LogRecipeRequest;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). Shapes today: /summary raw dto (200);
// /log + /log-recipe ResponseEntity<Void> (201); /calendar raw list (200) — the ApiResponse
// wrapper it used to carry was removed.
@ExtendWith(MockitoExtension.class)
class NutritionControllerTest {

    @Mock NutritionService service;

    @InjectMocks NutritionController controller;

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

    @Test
    void getSummary_returns200_withRawNutritionSummaryResponseAsBody() {
        NutritionSummaryResponse dto = new NutritionSummaryResponse();
        when(service.getSummary(1L, FROM, TO)).thenReturn(dto);

        ResponseEntity<NutritionSummaryResponse> res = controller.getSummary(1L, FROM, TO);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
    }

    @Test
    void logMeal_returns201_noBody_andForwardsRequestFieldsToService() {
        LogMealRequest request = new LogMealRequest();
        request.setUserId(1L);
        request.setItemName("banana");
        request.setQuantityGrams(118.0);

        ResponseEntity<Void> res = controller.logMeal(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNull();
        verify(service).logMeal(1L, "banana", 118.0);
    }

    @Test
    void logRecipe_returns201_noBody_andForwardsRecipeIdAndServingsToService() {
        LogRecipeRequest request = new LogRecipeRequest();
        request.setRecipeId(5L);
        request.setServings(2.0);

        ResponseEntity<Void> res = controller.logRecipe(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).logRecipe(5L, 2.0);
    }

    @Test
    void logRecipe_serviceThrowsForUnknownRecipe_propagatesNotFound() {
        LogRecipeRequest request = new LogRecipeRequest();
        request.setRecipeId(999L);
        request.setServings(1.0);
        org.mockito.Mockito.doThrow(new NotFoundException("Recipe not found: 999"))
                .when(service).logRecipe(999L, 1.0);

        assertThatThrownBy(() -> controller.logRecipe(request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getCalendar_returns200_withRawDailyBreakdownListAsBody_matchingSummary() {
        List<DailyNutritionSummary> breakdown = List.of(
                new DailyNutritionSummary(LocalDate.of(2026, 8, 1), 0.0, 0.0, 0.0, List.of()));
        when(service.getDailyBreakdown(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3)))
                .thenReturn(breakdown);

        ResponseEntity<List<DailyNutritionSummary>> res =
                controller.getCalendar(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(breakdown); // raw list, no ApiResponse envelope
    }

    @Test
    void getCalendar_delegatesToGetDailyBreakdownWithNoUserScoping() {
        when(service.getDailyBreakdown(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3)))
                .thenReturn(List.of());

        controller.getCalendar(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));

        // Marker for the still-open "no userId param" gap: only start/end are passed.
        verify(service).getDailyBreakdown(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));
    }
}
