package com.example.billing.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The invoice data returned by the API. "overdue", "daysOverdue" and the
 * can... flags are calculated, so the frontend knows which buttons to show.
 */
public record InvoiceResponse(
        String id,
        String invoiceNumber,
        String customerId,
        String customerName,
        List<Item> items,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal taxableAmount,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        InvoiceStatus status,
        LocalDate issueDate,
        LocalDate dueDate,
        boolean overdue,
        long daysOverdue,
        boolean canEdit,
        boolean canIssue,
        boolean canCancel,
        boolean canRecordPayment,
        boolean canDelete,
        String notes,
        Instant issuedAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {

    public record Item(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public static InvoiceResponse from(Invoice invoice, String customerName, LocalDate today) {
        List<Item> items = invoice.getItems().stream()
                .map(i -> new Item(i.getDescription(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                .toList();
        InvoiceStatus status = invoice.getStatus();
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getCustomerId(),
                customerName,
                items,
                invoice.getSubtotal(),
                invoice.getDiscountAmount(),
                invoice.getTaxableAmount(),
                invoice.getTaxRate(),
                invoice.getTaxAmount(),
                invoice.getTotalAmount(),
                invoice.getAmountPaid(),
                invoice.getBalanceDue(),
                status,
                invoice.getIssueDate(),
                invoice.getDueDate(),
                InvoiceRules.isOverdue(invoice.getDueDate(), status, today),
                InvoiceRules.daysOverdue(invoice.getDueDate(), status, today),
                InvoiceRules.canEdit(status),
                InvoiceRules.canIssue(status),
                InvoiceRules.canCancel(status, invoice.getAmountPaid()),
                InvoiceRules.canRecordPayment(status),
                InvoiceRules.canDelete(status),
                invoice.getNotes(),
                invoice.getIssuedAt(),
                invoice.getCancelledAt(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt());
    }
}
