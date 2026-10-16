package com.example.billing.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body for POST /api/invoices/{invoiceId}/payments and PUT /api/payments/{id}.
 * The invoice comes from the URL. A reference (transaction ID, cheque number, ...)
 * is required for every method except CASH.
 */
public record PaymentRequest(

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,

        @NotNull(message = "Payment date is required (YYYY-MM-DD)")
        @PastOrPresent(message = "Payment date cannot be in the future")
        LocalDate paymentDate,

        @NotNull(message = "Payment method is required")
        PaymentMethod method,

        @Size(max = 100, message = "Reference must be at most 100 characters")
        String reference,

        @Size(max = 1000, message = "Notes must be at most 1000 characters")
        String notes
) {
}
