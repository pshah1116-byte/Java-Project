package com.example.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Settings from application.properties that start with "billing.".
 *
 * @param defaultTaxRate           VAT percentage used when an invoice has none (13.00 for Nepal)
 * @param invoicePrefix            first part of invoice numbers, e.g. "INV" in INV-2026-0001
 * @param defaultPaymentTermsDays  days added to the issue date when no due date is given
 */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
        BigDecimal defaultTaxRate,
        String invoicePrefix,
        int defaultPaymentTermsDays
) {
    public BillingProperties {
        if (defaultTaxRate == null) {
            defaultTaxRate = new BigDecimal("13.00");
        }
        if (invoicePrefix == null || invoicePrefix.isBlank()) {
            invoicePrefix = "INV";
        }
        if (defaultPaymentTermsDays <= 0) {
            defaultPaymentTermsDays = 15;
        }
    }
}
