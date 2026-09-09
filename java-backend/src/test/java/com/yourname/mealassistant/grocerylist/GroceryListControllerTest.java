package com.yourname.mealassistant.grocerylist;

import com.yourname.mealassistant.grocerylist.dto.GroceryListRequest;
import com.yourname.mealassistant.grocerylist.dto.GroceryListResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). Raw GroceryListResponse bodies; generate ->
// 201, the rest -> 200. Thin pass-through to GroceryListService.
@ExtendWith(MockitoExtension.class)
class GroceryListControllerTest {

    @Mock GroceryListService service;

    @InjectMocks GroceryListController controller;

    private static GroceryListResponse response() {
        return new GroceryListResponse(7L, List.of(), BigDecimal.ZERO, false, false, List.of());
    }

    @Test
    void generate_returns201Created_withTheGroceryListResponseDirectlyAsBody() {
        GroceryListRequest request = new GroceryListRequest(7L);
        GroceryListResponse dto = response();
        when(service.generateGroceryList(request)).thenReturn(dto);

        ResponseEntity<GroceryListResponse> res = controller.generate(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).generateGroceryList(request);
    }

    @Test
    void get_returns200_withTheResponseAsBody_andForwardsMealPlanIdPathVar() {
        GroceryListResponse dto = response();
        when(service.getGroceryList(7L)).thenReturn(dto);

        ResponseEntity<GroceryListResponse> res = controller.get(7L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).getGroceryList(7L);
    }

    @Test
    void setPurchased_returns200_withTheRefreshedListAsBody_andForwardsItemIdAndPurchasedParam() {
        GroceryListResponse dto = response();
        when(service.setPurchased(99L, true)).thenReturn(dto);

        ResponseEntity<GroceryListResponse> res = controller.setPurchased(99L, true);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).setPurchased(99L, true);
    }

    @Test
    void setPurchased_falseAlsoForwarded_forUncheckingWhileShopping() {
        when(service.setPurchased(99L, false)).thenReturn(response());

        controller.setPurchased(99L, false);

        verify(service).setPurchased(99L, false);
    }
}
