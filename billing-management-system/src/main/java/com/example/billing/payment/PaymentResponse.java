package com.example.billing.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PaymentResponse(
        String id,
        String invoiceId,
        String invoiceNumber,
        String customerId,
        String customerName,
        BigDecimal amount,
        LocalDate paymentDate,
        PaymentMethod method,
        String reference,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment p, String invoiceNumber, String customerName) {
        return new PaymentResponse(p.getId(), p.getInvoiceId(), invoiceNumber, p.getCustomerId(), customerName,
                p.getAmount(), p.getPaymentDate(), p.getMethod(), p.getReference(), p.getNotes(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
