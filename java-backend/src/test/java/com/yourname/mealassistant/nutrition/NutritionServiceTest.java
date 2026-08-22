package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.client.NutritionApiClient;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NutritionServiceTest {

    @Mock NutritionApiClient nutritionApiClient;
    @Mock NutritionInfoRepository nutritionInfoRepository;
    @Mock NutritionLogRepository nutritionLogRepository;

    @InjectMocks NutritionService service;

    private NutritionInfo cachedChicken;

    @BeforeEach
    void setUp() {
        cachedChicken = new NutritionInfo();
        cachedChicken.setItemName("chicken");
        cachedChicken.setBaseQuantity(100.0);
        cachedChicken.setCalories(165.0);
        cachedChicken.setProtein(31.0);
        cachedChicken.setCarbs(0.0);
        cachedChicken.setFats(3.6);
        cachedChicken.setFibers(0.0);
    }

    // ---- getOrFetchNutritionInfo ----

    @Test
    void getOrFetchNutritionInfo_cacheHit_doesNotCallApi() {
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        NutritionInfo result = service.getOrFetchNutritionInfo("chicken");

        assertThat(result).isEqualTo(cachedChicken);
        verify(nutritionApiClient, never()).searchFoodByName(any());
    }

    @Test
    void getOrFetchNutritionInfo_cacheMiss_callsApiAndSaves() {
        when(nutritionInfoRepository.findById("tuna")).thenReturn(Optional.empty());
        // Return null response to simulate an API call with no food data
        when(nutritionApiClient.searchFoodByName("tuna")).thenReturn(null);
        when(nutritionInfoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NutritionInfo result = service.getOrFetchNutritionInfo("tuna");

        verify(nutritionApiClient, times(1)).searchFoodByName("tuna");
        verify(nutritionInfoRepository, times(1)).save(any(NutritionInfo.class));
        assertThat(result.getItemName()).isEqualTo("tuna");
    }

    // ---- logMeal ----

    @Test
    void logMeal_savesLogEntryToRepository() {
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        service.logMeal(1L, "chicken", 200.0);

        verify(nutritionLogRepository, times(1)).save(any(NutritionLog.class));
    }

    // ---- getSummary ----

    @Test
    void getSummary_noLogs_returnsZeroTotals() {
        when(nutritionLogRepository.findByUserId(1L)).thenReturn(List.of());

        NutritionSummaryResponse result = service.getSummary(1L);

        assertThat(result.getTotalCalories()).isEqualTo(0.0);
        assertThat(result.getTotalProtein()).isEqualTo(0.0);
    }

    @Test
    void getSummary_withLogs_calculatesCorrectMacros() {
        // User ate 200g of chicken (2x the 100g base)
        NutritionLog log = new NutritionLog();
        log.setUserId(1L);
        log.setItemName("chicken");
        log.setQuantityGrams(200.0);

        when(nutritionLogRepository.findByUserId(1L)).thenReturn(List.of(log));
        when(nutritionInfoRepository.findById("chicken")).thenReturn(Optional.of(cachedChicken));

        NutritionSummaryResponse result = service.getSummary(1L);

        // 200g / 100g = 2x multiplier => 165 * 2 = 330 calories
        assertThat(result.getTotalCalories()).isEqualTo(330.0);
        // 31 * 2 = 62g protein
        assertThat(result.getTotalProtein()).isEqualTo(62.0);
    }
}
