package com.example.billing.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Plain Java money calculations (no database), easy to unit test.
 *
 *   line total     = quantity x unit price                  (rounded to 2 decimals)
 *   subtotal       = sum of line totals
 *   taxable amount = subtotal - discount
 *   VAT            = taxable amount x tax rate / 100       (rounded to 2 decimals)
 *   total          = taxable amount + VAT
 *
 * Rounding is HALF_UP to the paisa (0.01), the usual rule for invoices.
 */
public final class InvoiceCalculator {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** The result of a calculation. */
    public record Totals(
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal taxableAmount,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal totalAmount
    ) {
    }

    /** A quantity and unit price pair, the only inputs a line total needs. */
    public record Line(BigDecimal quantity, BigDecimal unitPrice) {
    }

    private InvoiceCalculator() {
    }

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice) {
        return money(quantity.multiply(unitPrice));
    }

    public static Totals calculate(List<Line> lines, BigDecimal discountAmount, BigDecimal taxRate) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line line : lines) {
            subtotal = subtotal.add(lineTotal(line.quantity(), line.unitPrice()));
        }
        subtotal = money(subtotal);
        BigDecimal discount = money(discountAmount);
        BigDecimal rate = money(taxRate);
        BigDecimal taxable = money(subtotal.subtract(discount));
        BigDecimal tax = money(taxable.multiply(rate).divide(HUNDRED, 10, ROUNDING));
        BigDecimal total = money(taxable.add(tax));
        return new Totals(subtotal, discount, taxable, rate, tax, total);
    }

    public static BigDecimal balanceDue(BigDecimal totalAmount, BigDecimal amountPaid) {
        return money(money(totalAmount).subtract(money(amountPaid)));
    }

    /** Which status an issued invoice should have, given how much has been paid. */
    public static InvoiceStatus statusForPayments(BigDecimal totalAmount, BigDecimal amountPaid) {
        BigDecimal paid = money(amountPaid);
        if (paid.signum() == 0) {
            return InvoiceStatus.ISSUED;
        }
        if (paid.compareTo(money(totalAmount)) >= 0) {
            return InvoiceStatus.PAID;
        }
        return InvoiceStatus.PARTIALLY_PAID;
    }
}
