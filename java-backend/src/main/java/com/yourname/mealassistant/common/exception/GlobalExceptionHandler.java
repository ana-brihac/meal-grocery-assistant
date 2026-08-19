package com.yourname.mealassistant.common.exception;

import com.yourname.mealassistant.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice   // TODO: look up what this annotation does vs @ControllerAdvice
public class GlobalExceptionHandler {
	
	@ExceptionHandler(Exception.class)
   	public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
		return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail(e.getMessage()));
	}
}