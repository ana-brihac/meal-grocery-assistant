package com.yourname.mealassistant.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Every error response is an RFC 9457 ProblemDetail (Content-Type: application/problem+json),
// serialized by Spring: {type, title, status, detail}. The raw exception message goes in
// `detail`. Mapped cases:
//   - NotFoundException     -> 404  (a referenced plan / recipe / slot / item does not exist)
//   - BadRequestException   -> 400  (a request the controller/service rejected up front)
//   - NutritionApiException -> 502  (USDA returned 5xx / 429 — see NutritionApiClient)
//   - anything else          -> 500
// Note: the catch-all still surfaces `e.getMessage()` verbatim, which can leak internal detail;
// and there's no logging here — check the application console for stack traces. A bare
// IllegalArgumentException (e.g. from a library) is deliberately NOT remapped — it hits the 500
// catch-all so a genuine internal bug isn't disguised as a client error.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NutritionApiException.class)
    public ProblemDetail handleNutritionApi(NutritionApiException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
}
