package com.yourname.mealassistant.receipt;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.yourname.mealassistant.common.exception.BadRequestException;


@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {
    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    // Fire-and-forget: the multipart file is read synchronously, then OCR/parse/save run in the
    // background. Returns 202 Accepted immediately — there is no status endpoint to poll for the
    // outcome (see docs/known-issues.md). An unreadable upload is a 400 before any hand-off.
    @PostMapping("/upload")
    public ResponseEntity<String> uploadReceipt(@RequestParam("file") MultipartFile file) {
        byte[] fileBytes;
        String contentType;
        try {
            fileBytes = file.getBytes();
            contentType = file.getContentType();
        } catch (Exception e) {
            throw new BadRequestException("Failed to read file: " + e.getMessage(), e);
        }
        receiptService.processReceiptAsync(fileBytes, contentType);
        return ResponseEntity.accepted().body("Receipt uploaded and processing in background");
    }
}
