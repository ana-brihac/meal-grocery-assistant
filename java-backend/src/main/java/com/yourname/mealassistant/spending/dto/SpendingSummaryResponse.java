package com.yourname.mealassistant.spending.dto;

public class SpendingSummaryResponse {
    private Double totalSpent;

    public SpendingSummaryResponse() {}

    public SpendingSummaryResponse(Double totalSpent) {
        this.totalSpent = totalSpent;
    }
    
    public Double getTotalSpent() {
        return totalSpent;
    }
    
    public void setTotalSpent(Double totalSpent) {
        this.totalSpent = totalSpent;
    }
}
