package com.example.billing.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** One line in an invoice request. The line total is calculated by the server. */
public record InvoiceItemRequest(

        @NotBlank(message = "Item description is required")
        @Size(max = 200, message = "Item description must be at most 200 characters")
        String description,

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.01", message = "Quantity must be greater than 0")
        @Digits(integer = 9, fraction = 2, message = "Quantity can have at most 2 decimal places")
        BigDecimal quantity,

        @NotNull(message = "Unit price is required")
        @DecimalMin(value = "0.00", message = "Unit price cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "Unit price can have at most 2 decimal places")
        BigDecimal unitPrice
) {
}
