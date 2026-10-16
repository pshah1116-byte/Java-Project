package com.example.billing.invoice;

import com.example.billing.support.AbstractMongoIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Invoice totals, defaults, numbering, draft-only editing, issuing, cancelling and overdue. */
class InvoiceApiIntegrationTest extends AbstractMongoIntegrationTest {

    @Test
    void createDraftCalculatesTotalsOnTheServer() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(invoiceBody(customerId, today, today.plusDays(15)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.invoiceNumber").doesNotExist())
                .andExpect(jsonPath("$.customerName").value("Ram Sharma"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].lineTotal").value(3000.00))
                .andExpect(jsonPath("$.subtotal").value(3999.99))
                .andExpect(jsonPath("$.discountAmount").value(100.00))
                .andExpect(jsonPath("$.taxableAmount").value(3899.99))
                .andExpect(jsonPath("$.taxRate").value(13.00))
                .andExpect(jsonPath("$.taxAmount").value(507.00))
                .andExpect(jsonPath("$.totalAmount").value(4406.99))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.canRecordPayment").value(false));
    }

    @Test
    void defaultsAreTodayAndFifteenDayTerms() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        Map<String, Object> request = invoiceBody(customerId, today, today);
        request.remove("issueDate");
        request.remove("dueDate");

        MvcResult result = postJson("/api/invoices", request);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("issueDate").asText()).isEqualTo(today.toString());
        assertThat(body(result).get("dueDate").asText()).isEqualTo(today.plusDays(15).toString());
    }

    @Test
    void invalidInvoicesAreRejected() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);

        // due date before issue date
        MvcResult dates = postJson("/api/invoices", invoiceBody(customerId, today, today.minusDays(1)));
        assertThat(dates.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(dates).get("message").asText()).contains("cannot be earlier than the issue date");

        // discount larger than subtotal
        Map<String, Object> discount = invoiceBody(customerId, today, today.plusDays(5));
        discount.put("discountAmount", 5000);
        MvcResult tooMuch = postJson("/api/invoices", discount);
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooMuch).get("message").asText()).contains("cannot be more than the subtotal");

        // no items, bad item quantity
        Map<String, Object> noItems = invoiceBody(customerId, today, today.plusDays(5));
        noItems.put("items", List.of());
        assertThat(postJson("/api/invoices", noItems).getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> badQty = invoiceBody(customerId, today, today.plusDays(5));
        badQty.put("items", List.of(item("Thing", 0, 100)));
        MvcResult qty = postJson("/api/invoices", badQty);
        assertThat(qty.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(qty).get("fieldErrors").has("items[0].quantity")).isTrue();

        // missing customer
        MvcResult noCustomer = postJson("/api/invoices", invoiceBody("0123456789abcdef01234567", today, today.plusDays(5)));
        assertThat(noCustomer.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noCustomer).get("message").asText()).contains("does not exist");
    }

    @Test
    void inactiveCustomerCannotBeInvoiced() throws Exception {
        String customerId = createCustomer("Old Client", "old@example.com", "INACTIVE");
        MvcResult result = postJson("/api/invoices", invoiceBody(customerId, today, today.plusDays(5)));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asText()).contains("INACTIVE");
    }

    @Test
    void draftCanBeEditedAndDeleted() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        String id = createDraft(customerId, today, today.plusDays(15));

        Map<String, Object> update = invoiceBody(customerId, today, today.plusDays(30));
        update.put("items", List.of(item("Consulting", 10, 2000)));
        update.put("discountAmount", 0);
        update.put("taxRate", 0);
        mockMvc.perform(put("/api/invoices/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalAmount").value(20000.00))
                .andExpect(jsonPath("$.dueDate").value(today.plusDays(30).toString()));

        mockMvc.perform(delete("/api/invoices/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/invoices/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void issuingAssignsSequentialNumbersAndLocksTheInvoice() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        int year = today.getYear();

        String first = createDraft(customerId, today, today.plusDays(15));
        String second = createDraft(customerId, today, today.plusDays(15));

        assertThat(issue(first).get("invoiceNumber").asText()).isEqualTo("INV-" + year + "-0001");
        var issued = issue(second);
        assertThat(issued.get("invoiceNumber").asText()).isEqualTo("INV-" + year + "-0002");
        assertThat(issued.get("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.get("balanceDue").decimalValue()).isEqualByComparingTo("4406.99");
        assertThat(issued.get("canEdit").asBoolean()).isFalse();
        assertThat(issued.get("canRecordPayment").asBoolean()).isTrue();

        // locked: no edit, no delete, no second issue
        mockMvc.perform(put("/api/invoices/" + second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(invoiceBody(customerId, today, today.plusDays(15)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("can no longer be edited")));
        mockMvc.perform(delete("/api/invoices/" + second)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/invoices/" + second + "/issue")).andExpect(status().isBadRequest());

        // search by number
        mockMvc.perform(get("/api/invoices").param("search", year + "-0002"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(second));
    }

    @Test
    void zeroTotalInvoiceCannotBeIssued() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        Map<String, Object> free = invoiceBody(customerId, today, today.plusDays(5));
        free.put("items", List.of(item("Free sample", 1, 0)));
        free.put("discountAmount", 0);
        String id = body(postJson("/api/invoices", free)).get("id").asText();

        mockMvc.perform(post("/api/invoices/" + id + "/issue"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("total of 0")));
    }

    @Test
    void cancellingFollowsThePaymentRule() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);

        String unpaid = createIssuedInvoice(customerId);
        mockMvc.perform(post("/api/invoices/" + unpaid + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.balanceDue").value(0))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        mockMvc.perform(post("/api/invoices/" + unpaid + "/cancel")).andExpect(status().isBadRequest());

        String partlyPaid = createIssuedInvoice(customerId);
        pay(partlyPaid, 500, "CASH", null);
        mockMvc.perform(post("/api/invoices/" + partlyPaid + "/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Delete those payments first")));
    }

    @Test
    void overdueEndpointListsOnlyOpenInvoicesPastDue() throws Exception {
        String customerId = createCustomer("Ram Sharma", "ram@example.com", null);
        LocalDate past = today.minusDays(40);

        String overdue = createDraft(customerId, past, today.minusDays(10));
        issue(overdue);

        String paidLate = createDraft(customerId, past, today.minusDays(10));
        issue(paidLate);
        pay(paidLate, 4406.99, "CASH", null);

        createDraft(customerId, past, today.minusDays(10));                   // draft: never overdue
        createIssuedInvoice(customerId);                                    // not yet due

        mockMvc.perform(get("/api/invoices/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(overdue))
                .andExpect(jsonPath("$[0].overdue").value(true))
                .andExpect(jsonPath("$[0].daysOverdue").value(10));
    }

    @Test
    void invoicesCanBeFilteredByStatusAndCustomer() throws Exception {
        String ram = createCustomer("Ram Sharma", "ram@example.com", null);
        String sita = createCustomer("Sita Traders", "sita@example.com", null);
        createDraft(ram, today, today.plusDays(5));
        createIssuedInvoice(ram);
        createIssuedInvoice(sita);

        mockMvc.perform(get("/api/invoices").param("status", "ISSUED")).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/invoices").param("customerId", ram)).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/invoices").param("customerId", ram).param("status", "DRAFT"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/customers/" + sita + "/invoices")).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/invoices").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices/abc")).andExpect(status().isBadRequest());
    }
}
