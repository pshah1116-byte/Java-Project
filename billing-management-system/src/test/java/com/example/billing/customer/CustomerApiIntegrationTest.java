package com.example.billing.customer;

import com.example.billing.support.AbstractMongoIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer CRUD, duplicate emails, validation, search, deletion rule and account summary. */
class CustomerApiIntegrationTest extends AbstractMongoIntegrationTest {

    @Test
    void customerCrud() throws Exception {
        String id = body(mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(customerBody("Himalayan Traders", "Accounts@Himalayan.com", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value("accounts@himalayan.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()).get("id").asText();

        mockMvc.perform(get("/api/customers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Himalayan Traders"))
                .andExpect(jsonPath("$.panNumber").value("123456789"));

        Map<String, Object> update = customerBody("Himalayan Traders Pvt. Ltd.", "accounts@himalayan.com", "INACTIVE");
        mockMvc.perform(put("/api/customers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Himalayan Traders Pvt. Ltd."))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(delete("/api/customers/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/customers/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        createCustomer("First", "same@example.com", null);
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(customerBody("Second", "SAME@example.com", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    void invalidCustomerIsRejected() throws Exception {
        Map<String, Object> request = customerBody("", "bad-email", null);
        request.put("panNumber", "12AB");
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.panNumber").exists());

        mockMvc.perform(get("/api/customers/not-an-id")).andExpect(status().isBadRequest());
    }

    @Test
    void searchAndFilters() throws Exception {
        createCustomer("Ram Sharma", "ram@example.com", null);
        createCustomer("Sita Traders", "sita@example.com", "INACTIVE");
        createCustomer("Hari Ram Store", "hari@example.com", null);

        mockMvc.perform(get("/api/customers").param("search", "ram"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/customers").param("status", "INACTIVE"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Sita Traders"));
        mockMvc.perform(get("/api/customers").param("search", "ram").param("status", "ACTIVE").param("customerType", "BUSINESS"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/customers").param("status", "SLEEPING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerWithInvoicesCannotBeDeleted() throws Exception {
        String id = createCustomer("Ram Sharma", "ram@example.com", null);
        createDraft(id, today, today.plusDays(10));

        mockMvc.perform(delete("/api/customers/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("1 invoice(s)")));
    }

    @Test
    void summaryShowsBilledPaidOutstandingAndOverdue() throws Exception {
        String id = createCustomer("Ram Sharma", "ram@example.com", null);

        createDraft(id, today, today.plusDays(10));                       // draft: not billed
        String paid = createIssuedInvoice(id);                             // 4406.99, fully paid
        pay(paid, 4406.99, "CASH", null);
        String overdue = createDraft(id, today.minusDays(30), today.minusDays(5));
        issue(overdue);                                                    // 4406.99, overdue
        pay(overdue, 1000, "ESEWA", "TXN-1");                              // 3406.99 left

        mockMvc.perform(get("/api/customers/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInvoices").value(3))
                .andExpect(jsonPath("$.draftInvoices").value(1))
                .andExpect(jsonPath("$.paidInvoices").value(1))
                .andExpect(jsonPath("$.unpaidInvoices").value(1))
                .andExpect(jsonPath("$.overdueInvoices").value(1))
                .andExpect(jsonPath("$.totalBilled").value(8813.98))
                .andExpect(jsonPath("$.totalPaid").value(5406.99))
                .andExpect(jsonPath("$.outstandingBalance").value(3406.99))
                .andExpect(jsonPath("$.overdueAmount").value(3406.99));
    }
}
