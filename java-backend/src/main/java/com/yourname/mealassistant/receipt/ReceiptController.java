package com.yourname.mealassistant.receipt;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.yourname.mealassistant.common.dto.ApiResponse;


@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {
    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping("/upload")
    public ApiResponse<String> uploadReceipt(@RequestParam("file") MultipartFile file) {
        try {
            byte[] fileBytes = file.getBytes();
            String contentType = file.getContentType();
            receiptService.processReceiptAsync(fileBytes, contentType);
            return ApiResponse.ok("Receipt uploaded and processing in background");
        } catch (Exception e) {
            return ApiResponse.fail("Failed to read file: " + e.getMessage());
        }
    }
}
