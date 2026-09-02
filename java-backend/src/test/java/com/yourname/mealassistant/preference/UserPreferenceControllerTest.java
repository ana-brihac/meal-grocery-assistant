package com.yourname.mealassistant.preference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). Both endpoints return a raw UserPreference
// (200). Service behaviour is covered by UserPreferenceServiceTest.
@ExtendWith(MockitoExtension.class)
class UserPreferenceControllerTest {

    @Mock UserPreferenceService service;

    @InjectMocks UserPreferenceController controller;

    private static UserPreference pref(double cal) {
        return new UserPreference(cal, 100.0, 30.0, new BigDecimal("100"));
    }

    @Test
    void getPreferences_returns200_withTheUserPreferenceAsBody() {
        UserPreference stored = pref(2000.0);
        when(service.getPreferences()).thenReturn(stored);

        ResponseEntity<UserPreference> res = controller.getPreferences();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(stored);
    }

    @Test
    void updatePreferences_returns200_withPersistedPrefsAsBody_andForwardsTheRequestBody() {
        UserPreference incoming = pref(2200.0);
        UserPreference persisted = pref(2200.0);
        persisted.setId(1L);
        when(service.savePreferences(incoming)).thenReturn(persisted);

        ResponseEntity<UserPreference> res = controller.updatePreferences(incoming);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(persisted);
        verify(service).savePreferences(incoming);
    }

    @Test
    void updatePreferences_returnsWhateverTheServiceReturns_notTheInputEcho() {
        UserPreference incoming = pref(1800.0);
        UserPreference persisted = pref(1800.0);
        persisted.setId(1L);
        when(service.savePreferences(incoming)).thenReturn(persisted);

        ResponseEntity<UserPreference> res = controller.updatePreferences(incoming);

        assertThat(res.getBody()).isSameAs(persisted).isNotSameAs(incoming);
    }
}
