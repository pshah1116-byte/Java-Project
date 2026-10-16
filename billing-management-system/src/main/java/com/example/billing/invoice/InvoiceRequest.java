package com.example.billing.invoice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Body for POST /api/invoices and PUT /api/invoices/{id} (drafts only).
 *
 * Optional fields and their defaults:
 *   issueDate      -> today
 *   dueDate        -> issueDate + billing.default-payment-terms-days (15)
 *   discountAmount -> 0
 *   taxRate        -> billing.default-tax-rate (13% VAT)
 *
 * Totals sent by a client are never trusted: the server always calculates them.
 */
public record InvoiceRequest(

        @NotBlank(message = "Customer is required")
        String customerId,

        LocalDate issueDate,

        LocalDate dueDate,

        @NotEmpty(message = "An invoice needs at least one item")
        @Size(max = 100, message = "An invoice can have at most 100 items")
        List<@Valid InvoiceItemRequest> items,

        @DecimalMin(value = "0.00", message = "Discount cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "Discount can have at most 2 decimal places")
        BigDecimal discountAmount,

        @DecimalMin(value = "0.00", message = "Tax rate cannot be negative")
        @DecimalMax(value = "100.00", message = "Tax rate cannot be more than 100%")
        @Digits(integer = 3, fraction = 2, message = "Tax rate can have at most 2 decimal places")
        BigDecimal taxRate,

        @Size(max = 2000, message = "Notes must be at most 2000 characters")
        String notes
) {
}
