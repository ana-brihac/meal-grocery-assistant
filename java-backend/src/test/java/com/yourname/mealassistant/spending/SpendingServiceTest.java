package com.yourname.mealassistant.spending;

import com.yourname.mealassistant.receipt.Receipt;
import com.yourname.mealassistant.receipt.ReceiptRepository;
import com.yourname.mealassistant.spending.dto.SpendingSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpendingServiceTest {

    @Mock ReceiptRepository receiptRepository;
    @InjectMocks SpendingService service;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 8, 31);

    @Test
    void getSpendingSummary_noReceipts_returnsZero() {
        when(receiptRepository.findByUserIdAndReceiptDateBetween(1L, FROM, TO)).thenReturn(List.of());

        SpendingSummaryResponse result = service.getSpendingSummary(1L, FROM, TO);

        assertThat(result.getTotalSpent()).isEqualTo(0.0);
    }

    @Test
    void getSpendingSummary_multipleReceipts_returnsSummedTotal() {
        Receipt r1 = new Receipt();
        r1.setUserId(1L);
        r1.setTotalAmount(new BigDecimal("25.50"));

        Receipt r2 = new Receipt();
        r2.setUserId(1L);
        r2.setTotalAmount(new BigDecimal("14.75"));

        when(receiptRepository.findByUserIdAndReceiptDateBetween(1L, FROM, TO)).thenReturn(List.of(r1, r2));

        SpendingSummaryResponse result = service.getSpendingSummary(1L, FROM, TO);

        // 25.50 + 14.75 = 40.25
        assertThat(result.getTotalSpent()).isEqualTo(40.25);
    }

    @Test
    void getSpendingSummary_receiptWithNullAmount_isSkippedGracefully() {
        Receipt r1 = new Receipt();
        r1.setUserId(1L);
        r1.setTotalAmount(new BigDecimal("10.00"));

        Receipt r2 = new Receipt(); // No total amount set (null)
        r2.setUserId(1L);

        when(receiptRepository.findByUserIdAndReceiptDateBetween(1L, FROM, TO)).thenReturn(List.of(r1, r2));

        SpendingSummaryResponse result = service.getSpendingSummary(1L, FROM, TO);

        // Only r1 should count
        assertThat(result.getTotalSpent()).isEqualTo(10.0);
    }
}
