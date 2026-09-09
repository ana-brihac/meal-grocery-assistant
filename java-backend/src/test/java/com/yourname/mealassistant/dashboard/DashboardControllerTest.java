package com.yourname.mealassistant.dashboard;

import com.yourname.mealassistant.dashboard.dto.DashboardSummaryResponse;
import com.yourname.mealassistant.nutrition.NutritionService;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.spending.SpendingService;
import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). GET /summary returns a raw
// DashboardSummaryResponse (200). No service of its own — fans out to NutritionService +
// SpendingService and converts the LocalDate params for the nutrition call.
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock NutritionService nutritionService;
    @Mock SpendingService spendingService;

    @InjectMocks DashboardController controller;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Test
    void getDashboardSummary_returns200_withRawDashboardSummaryResponse_containingBothSummaries() {
        NutritionSummaryResponse nutrition = new NutritionSummaryResponse();
        SpendingSummaryResponse spending = new SpendingSummaryResponse(12.0);
        when(nutritionService.getSummary(anyLong(), any(), any())).thenReturn(nutrition);
        when(spendingService.getSpendingSummary(anyLong(), any(), any())).thenReturn(spending);

        ResponseEntity<DashboardSummaryResponse> res = controller.getDashboardSummary(1L, FROM, TO);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getNutrition()).isSameAs(nutrition);
        assertThat(res.getBody().getSpending()).isSameAs(spending);
    }

    @Test
    void getDashboardSummary_convertsDatesForNutrition_startOfDayToEndOfDay() {
        when(nutritionService.getSummary(anyLong(), any(), any())).thenReturn(new NutritionSummaryResponse());
        when(spendingService.getSpendingSummary(anyLong(), any(), any())).thenReturn(new SpendingSummaryResponse(0.0));

        controller.getDashboardSummary(1L, FROM, TO);

        verify(nutritionService).getSummary(1L, FROM.atStartOfDay(), TO.atTime(23, 59, 59));
    }

    @Test
    void getDashboardSummary_passesRawLocalDatesToSpending() {
        when(nutritionService.getSummary(anyLong(), any(), any())).thenReturn(new NutritionSummaryResponse());
        when(spendingService.getSpendingSummary(anyLong(), any(), any())).thenReturn(new SpendingSummaryResponse(0.0));

        controller.getDashboardSummary(1L, FROM, TO);

        verify(spendingService).getSpendingSummary(1L, FROM, TO);
    }
}
