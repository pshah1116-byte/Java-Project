package com.example.billing.customer;

import java.math.BigDecimal;

/**
 * Returned by GET /api/customers/{id}/summary - an account statement in numbers.
 * Amounts ignore DRAFT and CANCELLED invoices.
 */
public record CustomerSummaryResponse(
        CustomerResponse customer,
        long totalInvoices,
        long draftInvoices,
        long unpaidInvoices,
        long paidInvoices,
        long overdueInvoices,
        BigDecimal totalBilled,
        BigDecimal totalPaid,
        BigDecimal outstandingBalance,
        BigDecimal overdueAmount
) {
}
