package com.example.billing.dashboard;

import com.example.billing.support.AbstractMongoIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every dashboard number comes from the database. */
class DashboardIntegrationTest extends AbstractMongoIntegrationTest {

    @Test
    void emptyDatabaseGivesZeros() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCustomers").value(0))
                .andExpect(jsonPath("$.totalInvoices").value(0))
                .andExpect(jsonPath("$.totalBilled").value(0))
                .andExpect(jsonPath("$.totalCollected").value(0))
                .andExpect(jsonPath("$.recentInvoices", hasSize(0)))
                .andExpect(jsonPath("$.recentPayments", hasSize(0)));
    }

    @Test
    void statisticsReflectTheDatabase() throws Exception {
        String active = createCustomer("Ram Sharma", "ram@example.com", null);
        createCustomer("Old Client", "old@example.com", "INACTIVE");

        createDraft(active, today, today.plusDays(5));                     // draft
        createIssuedInvoice(active);                                        // issued, 4406.99 open
        String partial = createIssuedInvoice(active);
        pay(partial, 1000, "CASH", null);                                   // 3406.99 open
        String paid = createIssuedInvoice(active);
        pay(paid, 4406.99, "CASH", null);                                   // paid
        String cancelled = createIssuedInvoice(active);
        mockMvc.perform(post("/api/invoices/" + cancelled + "/cancel")).andExpect(status().isOk());
        String overdue = createDraft(active, today.minusDays(30), today.minusDays(3));
        issue(overdue);                                                     // 4406.99 open and overdue

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCustomers").value(2))
                .andExpect(jsonPath("$.activeCustomers").value(1))
                .andExpect(jsonPath("$.totalInvoices").value(6))
                .andExpect(jsonPath("$.draftInvoices").value(1))
                .andExpect(jsonPath("$.issuedInvoices").value(2))
                .andExpect(jsonPath("$.partiallyPaidInvoices").value(1))
                .andExpect(jsonPath("$.paidInvoices").value(1))
                .andExpect(jsonPath("$.cancelledInvoices").value(1))
                .andExpect(jsonPath("$.overdueInvoices").value(1))
                .andExpect(jsonPath("$.totalBilled").value(17627.96))       // 4 x 4406.99
                .andExpect(jsonPath("$.totalCollected").value(5406.99))
                .andExpect(jsonPath("$.outstandingAmount").value(12220.97)) // 4406.99 + 3406.99 + 4406.99
                .andExpect(jsonPath("$.overdueAmount").value(4406.99))
                .andExpect(jsonPath("$.collectedThisMonth").value(5406.99))
                .andExpect(jsonPath("$.recentInvoices", hasSize(5)))
                .andExpect(jsonPath("$.overdueInvoiceList", hasSize(1)))
                .andExpect(jsonPath("$.recentPayments", hasSize(2)));
    }
}
