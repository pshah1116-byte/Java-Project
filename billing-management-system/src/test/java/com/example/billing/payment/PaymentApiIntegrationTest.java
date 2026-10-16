package com.example.billing.payment;

import com.example.billing.support.AbstractMongoIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments: partial and full payment, limits, references, dates, corrections and deletions. */
class PaymentApiIntegrationTest extends AbstractMongoIntegrationTest {

    @Test
    void partialThenFullPaymentUpdatesTheInvoice() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        String invoiceId = createIssuedInvoice(customerId); // total 4406.99

        MvcResult first = pay(invoiceId, 1000, "ESEWA", "ESW-001");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(first).get("invoiceNumber").asText()).startsWith("INV-");

        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.amountPaid").value(1000.00))
                .andExpect(jsonPath("$.balanceDue").value(3406.99))
                .andExpect(jsonPath("$.canCancel").value(false));

        assertThat(pay(invoiceId, 3406.99, "BANK_TRANSFER", "NIC-778").getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.balanceDue").value(0))
                .andExpect(jsonPath("$.canRecordPayment").value(false));

        mockMvc.perform(get("/api/invoices/" + invoiceId + "/payments"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void paymentRulesAreEnforced() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);

        // draft invoice: no payments
        String draft = createDraft(customerId, today, today.plusDays(5));
        MvcResult onDraft = pay(draft, 100, "CASH", null);
        assertThat(onDraft.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(onDraft).get("message").asText()).contains("Issue the invoice first");

        String invoiceId = createIssuedInvoice(customerId);

        // more than the balance
        MvcResult over = pay(invoiceId, 5000, "CASH", null);
        assertThat(over.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(over).get("message").asText()).contains("more than the balance due");

        // non-cash without reference
        MvcResult noRef = pay(invoiceId, 100, "KHALTI", null);
        assertThat(noRef.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noRef).get("message").asText()).contains("reference");

        // before the issue date
        MvcResult early = postJson("/api/invoices/" + invoiceId + "/payments",
                paymentBody(100, "CASH", null, today.minusDays(1)));
        assertThat(early.getResponse().getStatus()).isEqualTo(400);

        // future date and zero amount (validation)
        assertThat(postJson("/api/invoices/" + invoiceId + "/payments",
                paymentBody(100, "CASH", null, today.plusDays(1))).getResponse().getStatus()).isEqualTo(400);
        assertThat(pay(invoiceId, 0, "CASH", null).getResponse().getStatus()).isEqualTo(400);

        // unknown method
        assertThat(pay(invoiceId, 100, "BITCOIN", "x").getResponse().getStatus()).isEqualTo(400);

        // nothing was recorded by the failed attempts
        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.amountPaid").value(0));
    }

    @Test
    void deletingAPaymentReopensTheBalance() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        String invoiceId = createIssuedInvoice(customerId);
        String paymentId = body(pay(invoiceId, 4406.99, "CASH", null)).get("id").asText();

        mockMvc.perform(get("/api/invoices/" + invoiceId)).andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(delete("/api/payments/" + paymentId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/payments/" + paymentId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.balanceDue").value(4406.99));
    }

    @Test
    void correctingAPaymentRespectsTheBalance() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        String invoiceId = createIssuedInvoice(customerId);
        pay(invoiceId, 2000, "CASH", null);
        String second = body(pay(invoiceId, 1000, "CASH", null)).get("id").asText(); // balance now 1406.99

        // second payment may grow to 1000 + 1406.99 = 2406.99, not more
        mockMvc.perform(put("/api/payments/" + second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(paymentBody(2500, "CASH", null, today))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/payments/" + second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(paymentBody(2406.99, "CHEQUE", "CHQ-55", today))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("CHEQUE"));

        mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amountPaid").value(4406.99));
    }

    @Test
    void paymentsCanBeFilteredByMethodCustomerAndDate() throws Exception {
        String ram = createCustomer("Ram Sharma", "ram@example.com", null);
        String sita = createCustomer("Sita Traders", "sita@example.com", null);
        pay(createIssuedInvoice(ram), 100, "CASH", null);
        pay(createIssuedInvoice(ram), 200, "ESEWA", "E-1");
        pay(createIssuedInvoice(sita), 300, "ESEWA", "E-2");

        mockMvc.perform(get("/api/payments")).andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get("/api/payments").param("method", "ESEWA")).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/payments").param("customerId", ram)).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/payments").param("from", today.toString()).param("to", today.toString()))
                .andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get("/api/payments").param("from", today.plusDays(1).toString()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/payments").param("from", today.toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest());
    }
}
