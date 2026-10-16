package com.example.billing.invoice;

/**
 * DRAFT          - being prepared, can be edited or deleted, has no invoice number yet
 * ISSUED         - sent to the customer, locked, nothing paid yet
 * PARTIALLY_PAID - some payments received (set automatically)
 * PAID           - fully paid (set automatically)
 * CANCELLED      - voided; only possible while nothing has been paid
 */
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PARTIALLY_PAID,
    PAID,
    CANCELLED
}
