package com.yourname.mealassistant.receipt;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import com.yourname.mealassistant.common.client.OcrClient;
import com.yourname.mealassistant.receipt.parser.ReceiptParser;
import com.yourname.mealassistant.inventory.InventoryRepository;

@Service
public class ReceiptService {
    private final OcrClient ocrClient;
    private final ReceiptParser receiptParser;
    private final InventoryRepository inventoryRepository;

    public ReceiptService(OcrClient ocrClient, ReceiptParser receiptParser, InventoryRepository inventoryRepository) {
        this.ocrClient = ocrClient;
        this.receiptParser = receiptParser;
        this.inventoryRepository = inventoryRepository;
    }

    public CompletableFuture<Void> processReceiptAsync(byte[] fileBytes, String contentType) {
        CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
            try {
                return ocrClient.extractTextFromImage(fileBytes, contentType);
            } catch (Exception e) {
                throw new RuntimeException("OCR failed", e);
            }
        }).thenApply(result -> {
            return receiptParser.parseReceiptText(result);
        }).thenAccept(parsedItems -> {
            inventoryRepository.saveAll(parsedItems);
        }).exceptionally(ex -> {
            System.err.println("Background receipt processing failed: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });

        return future;
    }
}
