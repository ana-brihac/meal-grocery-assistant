package com.yourname.mealassistant.preference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// JUnit 5 + Mockito + AssertJ, no Spring context. UserPreferenceService treats user_preference
// as a single-row table: getPreferences() reads the first row or falls back to hardcoded
// defaults; savePreferences() upserts, pinning id=1 on insert and never wiping a stored
// mealPrepBatchSize with a null.
@ExtendWith(MockitoExtension.class)
class UserPreferenceServiceTest {

    @Mock UserPreferenceRepository userPreferenceRepository;

    @InjectMocks UserPreferenceService service;

    private static UserPreference pref(Double cal, Double protein, Double fiber, String budget, Integer batch) {
        UserPreference p = new UserPreference(cal, protein, fiber, budget == null ? null : new BigDecimal(budget));
        p.setMealPrepBatchSize(batch);
        return p;
    }

    // ---- getPreferences ----

    @Test
    void getPreferences_rowExists_returnsThatRow() {
        UserPreference stored = pref(1900.0, 105.0, 27.0, "70", 2);
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(stored));

        assertThat(service.getPreferences()).isSameAs(stored);
        verify(userPreferenceRepository, never()).save(any());
    }

    @Test
    void getPreferences_noRow_returnsHardcodedDefaults() {
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        UserPreference defaults = service.getPreferences();

        assertThat(defaults.getCalories()).isEqualTo(2000.0);
        assertThat(defaults.getProtein()).isEqualTo(100.0);
        assertThat(defaults.getFiber()).isEqualTo(30.0);
        assertThat(defaults.getWeeklyBudget()).isEqualByComparingTo("100");
        assertThat(defaults.getMealPrepBatchSize()).isEqualTo(1);
        verify(userPreferenceRepository, never()).save(any());
    }

    @Test
    void getPreferences_noRow_defaultInstanceHasNoId() {
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThat(service.getPreferences().getId()).isNull();
    }

    // ---- savePreferences ----

    @Test
    void savePreferences_noExistingRow_pinsSingletonIdAndCopiesAllFields() {
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(userPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.savePreferences(pref(1800.0, 120.0, 25.0, "90", 2));

        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(saved.capture());
        UserPreference row = saved.getValue();
        assertThat(row.getId()).isEqualTo(UserPreference.SINGLETON_ID);
        assertThat(row.getCalories()).isEqualTo(1800.0);
        assertThat(row.getProtein()).isEqualTo(120.0);
        assertThat(row.getFiber()).isEqualTo(25.0);
        assertThat(row.getWeeklyBudget()).isEqualByComparingTo("90");
        assertThat(row.getMealPrepBatchSize()).isEqualTo(2);
    }

    @Test
    void savePreferences_existingRow_updatesInPlaceAndKeepsItsId() {
        UserPreference existing = pref(2000.0, 100.0, 30.0, "100", 1);
        existing.setId(1L);
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(userPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.savePreferences(pref(2200.0, 130.0, 35.0, "120", 2));

        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getId()).isEqualTo(1L);
        assertThat(saved.getValue().getCalories()).isEqualTo(2200.0);
        assertThat(saved.getValue().getWeeklyBudget()).isEqualByComparingTo("120");
    }

    @Test
    void savePreferences_nullMealPrepBatchSize_leavesExistingStoredValueUntouched() {
        UserPreference existing = pref(2000.0, 100.0, 30.0, "100", 3);
        existing.setId(1L);
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(userPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.savePreferences(pref(2100.0, 110.0, 28.0, "95", null));

        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(saved.capture());
        assertThat(saved.getValue().getMealPrepBatchSize()).isEqualTo(3);
    }

    @Test
    void savePreferences_nonNullMealPrepBatchSize_overwritesStoredValue() {
        UserPreference existing = pref(2000.0, 100.0, 30.0, "100", 1);
        existing.setId(1L);
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(userPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.savePreferences(pref(2000.0, 100.0, 30.0, "100", 3));

        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(saved.capture());
        assertThat(saved.getValue().getMealPrepBatchSize()).isEqualTo(3);
    }

    @Test
    void savePreferences_returnsWhateverRepositorySaveReturns() {
        UserPreference persisted = pref(2000.0, 100.0, 30.0, "100", 1);
        persisted.setId(1L);
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(userPreferenceRepository.save(any())).thenReturn(persisted);

        assertThat(service.savePreferences(pref(1.0, 1.0, 1.0, "1", 1))).isSameAs(persisted);
    }

    @Test
    void savePreferences_existingRowAlreadyHasId_doesNotReassignSingletonId() {
        UserPreference existing = pref(2000.0, 100.0, 30.0, "100", 1);
        existing.setId(5L);
        when(userPreferenceRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
        when(userPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.savePreferences(pref(2100.0, 110.0, 28.0, "95", 1));

        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(saved.capture());
        // The `if (existing.getId() == null)` guard means an existing id is never overwritten.
        assertThat(saved.getValue().getId()).isEqualTo(5L);
    }
}
