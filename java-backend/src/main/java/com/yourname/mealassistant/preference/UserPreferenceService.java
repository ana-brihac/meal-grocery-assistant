package com.yourname.mealassistant.preference;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class UserPreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;

    public UserPreferenceService(UserPreferenceRepository userPreferenceRepository) {
        this.userPreferenceRepository = userPreferenceRepository;
    }

    public UserPreference getPreferences() {
        return userPreferenceRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> new UserPreference(2000.0, 100.0, 30.0, new BigDecimal("100")));
    }

    public UserPreference savePreferences(UserPreference preferences) {
        UserPreference existing = userPreferenceRepository.findFirstByOrderByIdAsc()
                .orElseGet(UserPreference::new);

        if (existing.getId() == null) {
            existing.setId(UserPreference.SINGLETON_ID);
        }

        existing.setCalories(preferences.getCalories());
        existing.setProtein(preferences.getProtein());
        existing.setFiber(preferences.getFiber());
        existing.setWeeklyBudget(preferences.getWeeklyBudget());
        // Null-safe so a PUT that omits the field doesn't wipe an existing meal-prep setting.
        if (preferences.getMealPrepBatchSize() != null) {
            existing.setMealPrepBatchSize(preferences.getMealPrepBatchSize());
        }

        return userPreferenceRepository.save(existing);
    }
}
