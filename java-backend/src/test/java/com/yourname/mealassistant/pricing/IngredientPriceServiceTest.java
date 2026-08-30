package com.yourname.mealassistant.pricing;

import com.yourname.mealassistant.common.client.OcrClient;
import com.yourname.mealassistant.pricing.dto.AddPriceRequest;
import com.yourname.mealassistant.pricing.dto.PriceTagPhotoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngredientPriceServiceTest {

    @Mock IngredientPriceRepository ingredientPriceRepository;
    @Mock OcrClient ocrClient;

    @InjectMocks IngredientPriceService service;

    private static IngredientPrice row(String name, String price, String mode, Double gramsPerItem) {
        IngredientPrice ip = new IngredientPrice();
        ip.setItemName(name);
        ip.setPrice(price == null ? null : new BigDecimal(price));
        ip.setPricingMode(mode);
        ip.setGramsPerItem(gramsPerItem);
        return ip;
    }

    // ---- upsertFromReceipt ----

    @Test
    void upsertFromReceipt_newName_insertsRowAsReceiptPerItem() {
        when(ingredientPriceRepository.findByItemName("flour")).thenReturn(Optional.empty());
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.upsertFromReceipt("Flour", new BigDecimal("2.49"));

        ArgumentCaptor<IngredientPrice> saved = ArgumentCaptor.forClass(IngredientPrice.class);
        verify(ingredientPriceRepository).save(saved.capture());
        IngredientPrice ip = saved.getValue();
        assertThat(ip.getItemName()).isEqualTo("flour");
        assertThat(ip.getPrice()).isEqualByComparingTo("2.49");
        assertThat(ip.getPricingMode()).isEqualTo(IngredientPrice.MODE_PER_ITEM);
        assertThat(ip.getSource()).isEqualTo(IngredientPrice.SOURCE_RECEIPT);
        assertThat(ip.getPreviousPrice()).isNull();
        assertThat(ip.getPriceChangedAt()).isNull();
    }

    @Test
    void upsertFromReceipt_normalizesNameBeforeLookup() {
        when(ingredientPriceRepository.findByItemName("milk")).thenReturn(Optional.empty());
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.upsertFromReceipt("Milk 1L", new BigDecimal("1.20"));

        verify(ingredientPriceRepository).findByItemName("milk");
    }

    @Test
    void upsertFromReceipt_samePrice_doesNotTouchChangeTracking() {
        when(ingredientPriceRepository.findByItemName("eggs")).thenReturn(Optional.of(row("eggs", "3.00", "PER_ITEM", null)));
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.upsertFromReceipt("eggs", new BigDecimal("3.00"));

        ArgumentCaptor<IngredientPrice> saved = ArgumentCaptor.forClass(IngredientPrice.class);
        verify(ingredientPriceRepository).save(saved.capture());
        assertThat(saved.getValue().getPreviousPrice()).isNull();
        assertThat(saved.getValue().getPriceChangedAt()).isNull();
    }

    @Test
    void upsertFromReceipt_differentPrice_recordsPreviousPriceAndTimestamp() {
        when(ingredientPriceRepository.findByItemName("eggs")).thenReturn(Optional.of(row("eggs", "3.00", "PER_ITEM", null)));
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        service.upsertFromReceipt("eggs", new BigDecimal("3.40"));

        ArgumentCaptor<IngredientPrice> saved = ArgumentCaptor.forClass(IngredientPrice.class);
        verify(ingredientPriceRepository).save(saved.capture());
        IngredientPrice ip = saved.getValue();
        assertThat(ip.getPrice()).isEqualByComparingTo("3.40");
        assertThat(ip.getPreviousPrice()).isEqualByComparingTo("3.00");
        assertThat(ip.getPriceChangedAt()).isAfter(before);
    }

    @Test
    void upsertFromReceipt_doesNotClobberManualModeAndGramsPerItem() {
        when(ingredientPriceRepository.findByItemName("apple")).thenReturn(Optional.of(row("apple", "2.00", "PER_KG", null)));
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.upsertFromReceipt("apple", new BigDecimal("2.30"));

        ArgumentCaptor<IngredientPrice> saved = ArgumentCaptor.forClass(IngredientPrice.class);
        verify(ingredientPriceRepository).save(saved.capture());
        assertThat(saved.getValue().getPricingMode()).isEqualTo(IngredientPrice.MODE_PER_KG);
    }

    @Test
    void upsertFromReceipt_nullOrZeroPrice_skipsSave() {
        service.upsertFromReceipt("mystery", null);
        service.upsertFromReceipt("mystery", BigDecimal.ZERO);

        verify(ingredientPriceRepository, never()).save(any());
        verify(ingredientPriceRepository, never()).findByItemName(anyString());
    }

    // ---- addManualPrice ----

    @Test
    void addManualPrice_setsModeUppercasedAndGramsPerItem() {
        when(ingredientPriceRepository.findByItemName("egg")).thenReturn(Optional.empty());
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.addManualPrice(new AddPriceRequest("egg", new BigDecimal("0.30"), "per_item", 50.0));

        ArgumentCaptor<IngredientPrice> saved = ArgumentCaptor.forClass(IngredientPrice.class);
        verify(ingredientPriceRepository).save(saved.capture());
        IngredientPrice ip = saved.getValue();
        assertThat(ip.getPricingMode()).isEqualTo(IngredientPrice.MODE_PER_ITEM);
        assertThat(ip.getGramsPerItem()).isEqualTo(50.0);
        assertThat(ip.getSource()).isEqualTo(IngredientPrice.SOURCE_MANUAL);
    }

    @Test
    void addManualPrice_invalidMode_throws() {
        assertThatThrownBy(() ->
                service.addManualPrice(new AddPriceRequest("egg", new BigDecimal("0.30"), "per_dozen", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- estimateIngredientCost ----

    @Test
    void estimateIngredientCost_perKg_scalesByGrams() {
        when(ingredientPriceRepository.findByItemName("carrot")).thenReturn(Optional.of(row("carrot", "1.80", "PER_KG", null)));

        Optional<BigDecimal> cost = service.estimateIngredientCost("carrot", 250);

        assertThat(cost).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.45"));
    }

    @Test
    void estimateIngredientCost_perItemWithGramsPerItem_roundsUpToWholeUnits() {
        when(ingredientPriceRepository.findByItemName("egg")).thenReturn(Optional.of(row("egg", "0.30", "PER_ITEM", 50.0)));

        Optional<BigDecimal> cost = service.estimateIngredientCost("egg", 120);

        // ceil(120 / 50) = 3 units -> 3 * 0.30
        assertThat(cost).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.90"));
    }

    @Test
    void estimateIngredientCost_perItemWithoutGramsPerItem_returnsEmpty() {
        when(ingredientPriceRepository.findByItemName("jam")).thenReturn(Optional.of(row("jam", "2.50", "PER_ITEM", null)));

        assertThat(service.estimateIngredientCost("jam", 30)).isEmpty();
    }

    @Test
    void estimateIngredientCost_noRow_returnsEmpty() {
        when(ingredientPriceRepository.findByItemName("saffron")).thenReturn(Optional.empty());

        assertThat(service.estimateIngredientCost("saffron", 1)).isEmpty();
    }

    // ---- addFromPhoto ----

    @Test
    void addFromPhoto_ocrsWithPriceTagPrompt_parsesAndUpserts() throws Exception {
        when(ocrClient.extractTextFromImage(any(), anyString(), anyString()))
                .thenReturn("```json\n{\"name\": \"Bananas\", \"price\": 1.29, \"unit\": \"kg\"}\n```");
        when(ingredientPriceRepository.findByItemName("bananas")).thenReturn(Optional.empty());
        when(ingredientPriceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PriceTagPhotoResponse response = service.addFromPhoto(new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(response.saved()).isTrue();
        assertThat(response.itemName()).isEqualTo("bananas");
        assertThat(response.price()).isEqualByComparingTo("1.29");
        assertThat(response.pricingMode()).isEqualTo(IngredientPrice.MODE_PER_KG);

        ArgumentCaptor<IngredientPrice> saved = ArgumentCaptor.forClass(IngredientPrice.class);
        verify(ingredientPriceRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(IngredientPrice.SOURCE_PRICE_TAG_PHOTO);
    }

    @Test
    void addFromPhoto_unparseableOcr_throws() throws Exception {
        when(ocrClient.extractTextFromImage(any(), anyString(), anyString())).thenReturn("no idea what this is");

        assertThatThrownBy(() -> service.addFromPhoto(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(RuntimeException.class);
        verify(ingredientPriceRepository, never()).save(any());
    }
}
