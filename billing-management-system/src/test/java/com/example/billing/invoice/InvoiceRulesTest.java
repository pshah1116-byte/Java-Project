package com.example.billing.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.example.billing.invoice.InvoiceStatus.CANCELLED;
import static com.example.billing.invoice.InvoiceStatus.DRAFT;
import static com.example.billing.invoice.InvoiceStatus.ISSUED;
import static com.example.billing.invoice.InvoiceStatus.PAID;
import static com.example.billing.invoice.InvoiceStatus.PARTIALLY_PAID;
import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the invoice life-cycle rules (no database needed). */
class InvoiceRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Test
    void onlyDraftsCanBeEditedDeletedOrIssued() {
        for (InvoiceStatus status : InvoiceStatus.values()) {
            boolean draft = status == DRAFT;
            assertThat(InvoiceRules.canEdit(status)).isEqualTo(draft);
            assertThat(InvoiceRules.canDelete(status)).isEqualTo(draft);
            assertThat(InvoiceRules.canIssue(status)).isEqualTo(draft);
        }
    }

    @Test
    void paymentsOnlyForIssuedOrPartiallyPaid() {
        assertThat(InvoiceRules.canRecordPayment(ISSUED)).isTrue();
        assertThat(InvoiceRules.canRecordPayment(PARTIALLY_PAID)).isTrue();
        assertThat(InvoiceRules.canRecordPayment(DRAFT)).isFalse();
        assertThat(InvoiceRules.canRecordPayment(PAID)).isFalse();
        assertThat(InvoiceRules.canRecordPayment(CANCELLED)).isFalse();
    }

    @Test
    void cancellingOnlyWhileNothingIsPaid() {
        assertThat(InvoiceRules.canCancel(DRAFT, null)).isTrue();
        assertThat(InvoiceRules.canCancel(ISSUED, new BigDecimal("0.00"))).isTrue();
        assertThat(InvoiceRules.canCancel(PARTIALLY_PAID, new BigDecimal("10.00"))).isFalse();
        assertThat(InvoiceRules.canCancel(PAID, new BigDecimal("100.00"))).isFalse();
        assertThat(InvoiceRules.canCancel(CANCELLED, BigDecimal.ZERO)).isFalse();
    }

    @Test
    void overdueOnlyForOpenInvoicesPastTheDueDate() {
        LocalDate yesterday = TODAY.minusDays(1);
        assertThat(InvoiceRules.isOverdue(yesterday, ISSUED, TODAY)).isTrue();
        assertThat(InvoiceRules.isOverdue(yesterday, PARTIALLY_PAID, TODAY)).isTrue();
        assertThat(InvoiceRules.isOverdue(yesterday, DRAFT, TODAY)).isFalse();
        assertThat(InvoiceRules.isOverdue(yesterday, PAID, TODAY)).isFalse();
        assertThat(InvoiceRules.isOverdue(yesterday, CANCELLED, TODAY)).isFalse();
        assertThat(InvoiceRules.isOverdue(TODAY, ISSUED, TODAY)).as("due today is not overdue").isFalse();
        assertThat(InvoiceRules.daysOverdue(TODAY.minusDays(7), ISSUED, TODAY)).isEqualTo(7);
        assertThat(InvoiceRules.daysOverdue(TODAY.minusDays(7), PAID, TODAY)).isZero();
    }
}
