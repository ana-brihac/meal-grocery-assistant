package com.yourname.mealassistant.pricing;

import com.yourname.mealassistant.common.exception.BadRequestException;
import com.yourname.mealassistant.pricing.dto.AddPriceRequest;
import com.yourname.mealassistant.pricing.dto.PriceTagPhotoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). Raw DTO bodies; POST "" -> 201, from-photo
// -> 200; an unreadable from-photo upload becomes a BadRequestException (-> 400 + ProblemDetail).
@ExtendWith(MockitoExtension.class)
class PricingControllerTest {

    @Mock IngredientPriceService service;

    @InjectMocks PricingController controller;

    @Test
    void listAll_returns200_withTheCatalogListAsBody() {
        List<IngredientPrice> catalog = List.of(new IngredientPrice(), new IngredientPrice());
        when(service.findAll()).thenReturn(catalog);

        ResponseEntity<List<IngredientPrice>> res = controller.listAll();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(catalog);
    }

    @Test
    void add_returns201Created_withTheSavedPriceAsBody_andForwardsTheAddPriceRequest() {
        AddPriceRequest request = new AddPriceRequest("egg", new BigDecimal("0.30"), "PER_ITEM", 50.0);
        IngredientPrice saved = new IngredientPrice();
        when(service.addManualPrice(request)).thenReturn(saved);

        ResponseEntity<IngredientPrice> res = controller.add(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isSameAs(saved);
        verify(service).addManualPrice(request);
    }

    @Test
    void add_serviceRejectsInvalidPricingMode_propagatesBadRequest() {
        AddPriceRequest request = new AddPriceRequest("egg", new BigDecimal("0.30"), "per_dozen", null);
        when(service.addManualPrice(request))
                .thenThrow(new BadRequestException("pricingMode must be PER_ITEM or PER_KG"));

        assertThatThrownBy(() -> controller.add(request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void fromPhoto_success_returns200_withThePriceTagPhotoResponseAsBody() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        byte[] bytes = {1, 2, 3};
        when(file.getBytes()).thenReturn(bytes);
        when(file.getContentType()).thenReturn("image/jpeg");
        PriceTagPhotoResponse dto =
                new PriceTagPhotoResponse("bananas", new BigDecimal("1.29"), "PER_KG", true, "raw ocr");
        when(service.addFromPhoto(bytes, "image/jpeg")).thenReturn(dto);

        ResponseEntity<PriceTagPhotoResponse> res = controller.fromPhoto(file);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
    }

    @Test
    void fromPhoto_fileReadThrows_maps400BadRequest_andDoesNotCallService() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getBytes()).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> controller.fromPhoto(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Failed to read file:");
        verify(service, never()).addFromPhoto(any(), any());
    }
}
