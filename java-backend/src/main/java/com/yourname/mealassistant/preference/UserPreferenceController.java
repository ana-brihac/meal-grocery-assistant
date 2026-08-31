package com.yourname.mealassistant.preference;

import com.yourname.mealassistant.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/preferences")
public class UserPreferenceController {

    private final UserPreferenceService service;

    public UserPreferenceController(UserPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<UserPreference>> getPreferences() {
        return ResponseEntity.ok(ApiResponse.ok(service.getPreferences()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<UserPreference>> updatePreferences(@RequestBody UserPreference preferences) {
        return ResponseEntity.ok(ApiResponse.ok(service.savePreferences(preferences)));
    }
}
