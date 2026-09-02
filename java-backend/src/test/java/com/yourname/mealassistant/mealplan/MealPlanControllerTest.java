package com.yourname.mealassistant.mealplan;

import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.mealplan.dto.MealPlanRequest;
import com.yourname.mealassistant.mealplan.dto.MealPlanResponse;
import com.yourname.mealassistant.mealplan.dto.SelectPlanRequest;
import com.yourname.mealassistant.mealplan.dto.SlotReplacementRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). Raw MealPlanResponse bodies; generate -> 201,
// the rest -> 200. Thin pass-through to MealPlanService (covered by MealPlanServiceTest).
@ExtendWith(MockitoExtension.class)
class MealPlanControllerTest {

    @Mock MealPlanService service;

    @InjectMocks MealPlanController controller;

    private static MealPlanResponse response(long id) {
        return new MealPlanResponse(id, "DRAFT", LocalDate.of(2026, 9, 1), null,
                new MealPlanResponse.Targets(2000.0, 100.0, 30.0, new BigDecimal("50")),
                List.of(), BigDecimal.ZERO, false, false, List.of());
    }

    @Test
    void generate_returns201Created_withTheMealPlanResponseDirectlyAsBody() {
        MealPlanRequest request = new MealPlanRequest(LocalDate.of(2026, 9, 1), null, null, null);
        MealPlanResponse dto = response(1L);
        when(service.generatePlan(request)).thenReturn(dto);

        ResponseEntity<MealPlanResponse> res = controller.generate(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).generatePlan(request);
    }

    @Test
    void history_returns200_withTheListOfPlansAsBody() {
        List<MealPlanResponse> plans = List.of(response(1L), response(2L));
        when(service.getHistory()).thenReturn(plans);

        ResponseEntity<List<MealPlanResponse>> res = controller.history();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(plans);
    }

    @Test
    void getOne_returns200_withThePlanAsBody_andForwardsPathId() {
        MealPlanResponse dto = response(42L);
        when(service.getPlan(42L)).thenReturn(dto);

        ResponseEntity<MealPlanResponse> res = controller.getOne(42L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).getPlan(42L);
    }

    @Test
    void getOne_serviceThrowsForUnknownId_propagatesNotFound() {
        when(service.getPlan(999L)).thenThrow(new NotFoundException("Meal plan not found: 999"));

        // GlobalExceptionHandler maps NotFoundException -> 404 + ProblemDetail at runtime
        // (see ApiErrorMappingTest); here we only assert it propagates from the handler method.
        assertThatThrownBy(() -> controller.getOne(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void replaceSlot_returns200_withThePlanAsBody_andForwardsPlanIdSlotIdAndBody() {
        SlotReplacementRequest body = new SlotReplacementRequest(List.of(7L));
        MealPlanResponse dto = response(1L);
        when(service.replaceSlot(1L, 2L, body)).thenReturn(dto);

        ResponseEntity<MealPlanResponse> res = controller.replaceSlot(1L, 2L, body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).replaceSlot(1L, 2L, body);
    }

    @Test
    void replaceSlot_nullBody_isAllowed_andPassedAsNullToTheService() {
        when(service.replaceSlot(1L, 2L, null)).thenReturn(response(1L));

        controller.replaceSlot(1L, 2L, null);

        verify(service).replaceSlot(1L, 2L, null);
    }

    @Test
    void select_returns200_withThePlanAsBody_andForwardsPlanIdAndSelectPlanRequest() {
        SelectPlanRequest body = new SelectPlanRequest(LocalDate.of(2026, 9, 8));
        MealPlanResponse dto = response(1L);
        when(service.selectForWeek(1L, body)).thenReturn(dto);

        ResponseEntity<MealPlanResponse> res = controller.select(1L, body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).selectForWeek(1L, body);
    }
}
