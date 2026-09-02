package com.yourname.mealassistant.common.exception;

// A client-side error (HTTP 400). Mapped to an RFC 9457 ProblemDetail by GlobalExceptionHandler.
// Used where a controller can tell the request itself is bad before any real work runs — e.g. an
// unreadable multipart upload (ReceiptController, PricingController#fromPhoto).
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
