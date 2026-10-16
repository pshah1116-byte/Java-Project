package com.example.billing.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for money calculations (no database needed). */
class InvoiceCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void calculatesSubtotalDiscountVatAndTotal() {
        InvoiceCalculator.Totals t = InvoiceCalculator.calculate(List.of(
                        new InvoiceCalculator.Line(d("2"), d("1500")),
                        new InvoiceCalculator.Line(d("1"), d("999.99"))),
                d("100"), d("13"));

        assertThat(t.subtotal()).isEqualByComparingTo("3999.99");
        assertThat(t.discountAmount()).isEqualByComparingTo("100.00");
        assertThat(t.taxableAmount()).isEqualByComparingTo("3899.99");
        assertThat(t.taxAmount()).isEqualByComparingTo("507.00"); // 506.9987 rounded half-up
        assertThat(t.totalAmount()).isEqualByComparingTo("4406.99");
        assertThat(t.totalAmount().scale()).isEqualTo(2);
    }

    @Test
    void lineTotalsRoundHalfUpToThePaisa() {
        assertThat(InvoiceCalculator.lineTotal(d("1.5"), d("333.33"))).isEqualByComparingTo("500.00"); // 499.995
        assertThat(InvoiceCalculator.lineTotal(d("3"), d("0.10"))).isEqualByComparingTo("0.30");
    }

    @Test
    void missingDiscountMeansZeroAndZeroTaxIsAllowed() {
        InvoiceCalculator.Totals t = InvoiceCalculator.calculate(
                List.of(new InvoiceCalculator.Line(BigDecimal.ONE, d("1000"))), null, BigDecimal.ZERO);
        assertThat(t.discountAmount()).isEqualByComparingTo("0");
        assertThat(t.taxAmount()).isEqualByComparingTo("0");
        assertThat(t.totalAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void statusFollowsTheAmountPaid() {
        BigDecimal total = d("4406.99");
        assertThat(InvoiceCalculator.statusForPayments(total, BigDecimal.ZERO)).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(InvoiceCalculator.statusForPayments(total, d("0.01"))).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(InvoiceCalculator.statusForPayments(total, d("4406.98"))).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(InvoiceCalculator.statusForPayments(total, d("4406.99"))).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void balanceDueIsTotalMinusPaid() {
        assertThat(InvoiceCalculator.balanceDue(d("4406.99"), d("1000"))).isEqualByComparingTo("3406.99");
        assertThat(InvoiceCalculator.balanceDue(d("4406.99"), d("4406.99"))).isEqualByComparingTo("0");
    }
}
