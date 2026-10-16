package com.example.billing.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Plain Java rules about what can happen to an invoice in each status (easy to unit test).
 *
 *   Edit / delete    : DRAFT only
 *   Issue            : DRAFT only
 *   Record payment   : ISSUED or PARTIALLY_PAID
 *   Cancel           : DRAFT, or ISSUED with nothing paid
 *   Overdue          : ISSUED or PARTIALLY_PAID and the due date is before today
 */
public final class InvoiceRules {

    /** Invoices the customer still owes money on. */
    public static final Set<InvoiceStatus> OPEN_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID));

    /** Invoices that count as billed (issued to the customer and not cancelled). */
    public static final Set<InvoiceStatus> BILLED_STATUSES = Collections.unmodifiableSet(
            EnumSet.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID));

    private InvoiceRules() {
    }

    public static boolean canEdit(InvoiceStatus status) {
        return status == InvoiceStatus.DRAFT;
    }

    public static boolean canDelete(InvoiceStatus status) {
        return status == InvoiceStatus.DRAFT;
    }

    public static boolean canIssue(InvoiceStatus status) {
        return status == InvoiceStatus.DRAFT;
    }

    public static boolean canRecordPayment(InvoiceStatus status) {
        return OPEN_STATUSES.contains(status);
    }

    public static boolean canCancel(InvoiceStatus status, BigDecimal amountPaid) {
        boolean nothingPaid = amountPaid == null || amountPaid.signum() == 0;
        return status == InvoiceStatus.DRAFT || (status == InvoiceStatus.ISSUED && nothingPaid);
    }

    public static boolean isOverdue(LocalDate dueDate, InvoiceStatus status, LocalDate today) {
        return dueDate != null && OPEN_STATUSES.contains(status) && dueDate.isBefore(today);
    }

    public static long daysOverdue(LocalDate dueDate, InvoiceStatus status, LocalDate today) {
        return isOverdue(dueDate, status, today) ? ChronoUnit.DAYS.between(dueDate, today) : 0;
    }
}
