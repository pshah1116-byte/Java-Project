package com.example.billing.dashboard;

import com.example.billing.invoice.InvoiceResponse;
import com.example.billing.payment.PaymentResponse;

import java.math.BigDecimal;
import java.util.List;

/** Returned by GET /api/dashboard/summary. Every number is calculated from MongoDB. */
public record DashboardSummaryResponse(
        long totalCustomers,
        long activeCustomers,
        long totalInvoices,
        long draftInvoices,
        long issuedInvoices,
        long partiallyPaidInvoices,
        long paidInvoices,
        long cancelledInvoices,
        long overdueInvoices,
        BigDecimal totalBilled,
        BigDecimal totalCollected,
        BigDecimal outstandingAmount,
        BigDecimal overdueAmount,
        BigDecimal collectedThisMonth,
        List<InvoiceResponse> recentInvoices,
        List<InvoiceResponse> overdueInvoiceList,
        List<PaymentResponse> recentPayments
) {
}
