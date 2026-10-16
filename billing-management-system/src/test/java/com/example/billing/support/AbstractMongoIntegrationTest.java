package com.example.billing.support;

import com.example.billing.customer.Customer;
import com.example.billing.invoice.Invoice;
import com.example.billing.invoice.InvoiceNumberGenerator;
import com.example.billing.payment.Payment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration tests.
 *
 * ISOLATION: tests use a SEPARATE database, "billing_management_test". Your real
 * "billing_management" database is never touched. The test collections are emptied
 * before each test (only after double-checking the database name).
 * If MongoDB is not running, the tests are SKIPPED with a message instead of failing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.data.mongodb.uri=mongodb://localhost:27017/" + AbstractMongoIntegrationTest.TEST_DATABASE)
public abstract class AbstractMongoIntegrationTest {

    public static final String TEST_DATABASE = "billing_management_test";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    protected final LocalDate today = LocalDate.now();

    @BeforeAll
    static void requireLocalMongo() {
        Assumptions.assumeTrue(isMongoReachable(),
                "MongoDB is not running on localhost:27017 - integration tests are skipped. Start MongoDB and run again.");
    }

    @BeforeEach
    void cleanTestDatabase() {
        assertThat(mongoTemplate.getDb().getName())
                .as("Integration tests must only ever use the test database")
                .isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), Payment.class);
        mongoTemplate.remove(new Query(), Invoice.class);
        mongoTemplate.remove(new Query(), Customer.class);
        mongoTemplate.remove(new Query(), InvoiceNumberGenerator.COUNTERS_COLLECTION);
    }

    private static boolean isMongoReachable() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://localhost:27017"))
                .applyToClusterSettings(c -> c.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    protected String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andReturn();
    }

    protected Map<String, Object> customerBody(String name, String email, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("email", email);
        map.put("phone", "+977 9800000000");
        map.put("address", "Kathmandu");
        map.put("panNumber", "123456789");
        map.put("customerType", "BUSINESS");
        map.put("notes", "Test customer");
        if (status != null) {
            map.put("status", status);
        }
        return map;
    }

    protected String createCustomer(String name, String email, String status) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(customerBody(name, email, status))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).get("id").asText();
    }

    protected Map<String, Object> item(String description, Object quantity, Object unitPrice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("description", description);
        map.put("quantity", quantity);
        map.put("unitPrice", unitPrice);
        return map;
    }

    /** Standard test invoice: 2 x 1500 + 1 x 999.99, discount 100, default 13% VAT -> total 4406.99. */
    protected Map<String, Object> invoiceBody(String customerId, LocalDate issueDate, LocalDate dueDate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("customerId", customerId);
        map.put("issueDate", issueDate.toString());
        map.put("dueDate", dueDate.toString());
        map.put("items", List.of(item("Website design", 2, 1500), item("Domain and hosting", 1, 999.99)));
        map.put("discountAmount", 100);
        map.put("notes", "Thank you for your business");
        return map;
    }

    protected String createDraft(String customerId, LocalDate issueDate, LocalDate dueDate) throws Exception {
        MvcResult result = postJson("/api/invoices", invoiceBody(customerId, issueDate, dueDate));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asText();
    }

    protected JsonNode issue(String invoiceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices/" + invoiceId + "/issue")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    /** Creates and issues an invoice dated today, due in 15 days. */
    protected String createIssuedInvoice(String customerId) throws Exception {
        String id = createDraft(customerId, today, today.plusDays(15));
        issue(id);
        return id;
    }

    protected Map<String, Object> paymentBody(Object amount, String method, String reference, LocalDate date) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("amount", amount);
        map.put("paymentDate", date.toString());
        map.put("method", method);
        if (reference != null) {
            map.put("reference", reference);
        }
        return map;
    }

    protected MvcResult pay(String invoiceId, Object amount, String method, String reference) throws Exception {
        return postJson("/api/invoices/" + invoiceId + "/payments", paymentBody(amount, method, reference, today));
    }
}
