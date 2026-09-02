package com.yourname.mealassistant.receipt;

import com.yourname.mealassistant.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). POST /upload: 202 + raw String on success;
// an unreadable file becomes a BadRequestException (-> 400 + ProblemDetail via
// GlobalExceptionHandler) before the async hand-off.
@ExtendWith(MockitoExtension.class)
class ReceiptControllerTest {

    @Mock ReceiptService receiptService;

    @InjectMocks ReceiptController controller;

    @Test
    void uploadReceipt_readsFileBytesAndContentType_thenDelegatesToProcessReceiptAsync() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        byte[] bytes = {1, 2, 3};
        when(file.getBytes()).thenReturn(bytes);
        when(file.getContentType()).thenReturn("image/png");

        controller.uploadReceipt(file);

        verify(receiptService).processReceiptAsync(bytes, "image/png");
    }

    @Test
    void uploadReceipt_success_returns202Accepted_withTheProcessingInBackgroundMessageDirectly() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getBytes()).thenReturn(new byte[]{1});
        when(file.getContentType()).thenReturn("image/jpeg");

        ResponseEntity<String> res = controller.uploadReceipt(file);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody()).isEqualTo("Receipt uploaded and processing in background");
    }

    @Test
    void uploadReceipt_fileGetBytesThrowsIOException_maps400BadRequest_andDoesNotCallService() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getBytes()).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> controller.uploadReceipt(file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Failed to read file:");
        verify(receiptService, never()).processReceiptAsync(any(), any());
    }

    @Test
    void uploadReceipt_nullContentType_isStillForwarded() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        byte[] bytes = {9};
        when(file.getBytes()).thenReturn(bytes);
        when(file.getContentType()).thenReturn(null);

        ResponseEntity<String> res = controller.uploadReceipt(file);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(receiptService).processReceiptAsync(bytes, null);
    }
}
