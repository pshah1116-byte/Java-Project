package com.example.billing.customer;

import java.time.Instant;

public record CustomerResponse(
        String id,
        String name,
        String email,
        String phone,
        String address,
        String panNumber,
        CustomerType customerType,
        CustomerStatus status,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getPhone(), c.getAddress(),
                c.getPanNumber(), c.getCustomerType(), c.getStatus(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
