package com.example.billing.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for POST /api/customers and PUT /api/customers/{id}.
 * "status" is optional: new customers become ACTIVE, updates without a status keep the current one.
 */
public record CustomerRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 120, message = "Email must be at most 120 characters")
        String email,

        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "^\\+?[0-9 ()\\-]{7,20}$",
                message = "Phone must be 7-20 characters: digits, spaces, (), - and an optional leading +")
        String phone,

        @Size(max = 255, message = "Address must be at most 255 characters")
        String address,

        @Pattern(regexp = "^$|^[0-9]{9}$", message = "PAN number must be exactly 9 digits")
        String panNumber,

        @NotNull(message = "Customer type is required (INDIVIDUAL or BUSINESS)")
        CustomerType customerType,

        CustomerStatus status,

        @Size(max = 2000, message = "Notes must be at most 2000 characters")
        String notes
) {
}
