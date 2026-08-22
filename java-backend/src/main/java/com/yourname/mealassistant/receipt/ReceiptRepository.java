package com.yourname.mealassistant.receipt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    List<Receipt> findByUserId(Long userId);
    List<Receipt> findByUserIdAndReceiptDateBetween(Long userId, java.time.LocalDate from, java.time.LocalDate to);
}
