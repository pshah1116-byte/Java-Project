package com.example.billing.invoice;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;

/** One line of an invoice. Stored inside the invoice document (embedded, no separate collection). */
public class InvoiceItem {

    private String description;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal quantity;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal unitPrice;

    /** quantity x unitPrice, rounded to 2 decimals. Calculated by the server. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal lineTotal;

    public InvoiceItem() {
    }

    public InvoiceItem(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
}
