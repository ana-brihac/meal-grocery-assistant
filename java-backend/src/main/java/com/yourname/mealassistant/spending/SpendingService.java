package com.yourname.mealassistant.spending;

import com.yourname.mealassistant.receipt.Receipt;
import com.yourname.mealassistant.receipt.ReceiptRepository;
import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class SpendingService {
    
    private final ReceiptRepository receiptRepository;
    
    public SpendingService(ReceiptRepository receiptRepository) {
        this.receiptRepository = receiptRepository;
    }

    public SpendingSummaryResponse getSpendingSummary(Long userId) {
        SpendingSummaryResponse total = new SpendingSummaryResponse();
        BigDecimal totalPrice = BigDecimal.ZERO;
        
        List<Receipt> receipts = receiptRepository.findByUserId(userId);
        
        for (Receipt x : receipts) {
            if (x.getTotalAmount() != null) {
                totalPrice = totalPrice.add(x.getTotalAmount()); 
            }
        }

        total.setTotalSpent(totalPrice.doubleValue());
        
        return total;
    }
}
