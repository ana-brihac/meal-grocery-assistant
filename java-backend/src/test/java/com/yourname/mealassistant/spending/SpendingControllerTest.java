package com.yourname.mealassistant.spending;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). GET /summary returns a raw
// SpendingSummaryResponse (200) — already at the target shape, no envelope.
@ExtendWith(MockitoExtension.class)
class SpendingControllerTest {

    @Mock SpendingService service;

    @InjectMocks SpendingController controller;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Test
    void getSummary_returns200_withRawSpendingSummaryResponse_notWrapped() {
        when(service.getSpendingSummary(1L, FROM, TO)).thenReturn(new SpendingSummaryResponse(40.25));

        ResponseEntity<SpendingSummaryResponse> res = controller.getSummary(1L, FROM, TO);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getTotalSpent()).isEqualTo(40.25);
    }

    @Test
    void getSummary_passesUserIdAndDateRangeStraightThroughToTheService() {
        when(service.getSpendingSummary(7L, FROM, TO)).thenReturn(new SpendingSummaryResponse(0.0));

        controller.getSummary(7L, FROM, TO);

        // No date massaging here (contrast DashboardController's atStartOfDay()/atTime(23,59,59)).
        verify(service).getSpendingSummary(7L, FROM, TO);
    }
}
