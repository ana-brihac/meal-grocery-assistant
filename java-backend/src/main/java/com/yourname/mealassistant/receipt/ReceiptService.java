package com.yourname.mealassistant.receipt;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import com.yourname.mealassistant.common.client.OcrClient;
import com.yourname.mealassistant.receipt.parser.ReceiptParser;
import com.yourname.mealassistant.inventory.InventoryRepository;
import com.yourname.mealassistant.pricing.IngredientPriceService;

@Service
public class ReceiptService {
    private final OcrClient ocrClient;
    private final ReceiptParser receiptParser;
    private final InventoryRepository inventoryRepository;
    // receipt lines also feed the ingredient_price catalog (Decided — see
    // pricing/IngredientPrice.java). Injected here so processReceiptAsync can upsert each parsed
    // line's price after it's saved to inventory.
    private final IngredientPriceService ingredientPriceService;

    public ReceiptService(OcrClient ocrClient, ReceiptParser receiptParser, InventoryRepository inventoryRepository,
                          IngredientPriceService ingredientPriceService) {
        this.ocrClient = ocrClient;
        this.receiptParser = receiptParser;
        this.inventoryRepository = inventoryRepository;
        this.ingredientPriceService = ingredientPriceService;
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
            // feed each priced line into the ingredient_price catalog. Best-effort — a
            // pricing hiccup shouldn't sink the receipt import (this whole chain is already
            // fire-and-forget), so per-item failures are swallowed. upsertFromReceipt itself
            // ignores lines with no usable price.
            for (var item : parsedItems) {
                try {
                    ingredientPriceService.upsertFromReceipt(item.getName(), item.getPrice());
                } catch (RuntimeException e) {
                    System.err.println("Price upsert failed for '" + item.getName() + "': " + e.getMessage());
                }
            }
        }).exceptionally(ex -> {
            System.err.println("Background receipt processing failed: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });

        return future;
    }
}
