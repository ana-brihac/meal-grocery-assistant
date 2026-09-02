package com.yourname.mealassistant.common.exception;

// A referenced resource (a plan, recipe, slot, grocery-list item, ...) does not exist.
// Mapped to an RFC 9457 ProblemDetail with HTTP 404 by GlobalExceptionHandler. Thrown from the
// `orElseThrow(...)` sites in the services.
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
