package com.yourname.mealassistant.preference;

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
    public ResponseEntity<UserPreference> getPreferences() {
        return ResponseEntity.ok(service.getPreferences());
    }

    @PutMapping
    public ResponseEntity<UserPreference> updatePreferences(@RequestBody UserPreference preferences) {
        return ResponseEntity.ok(service.savePreferences(preferences));
    }
}
