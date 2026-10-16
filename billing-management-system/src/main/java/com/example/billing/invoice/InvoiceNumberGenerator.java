package com.example.billing.invoice;

import com.example.billing.config.BillingProperties;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Creates sequential invoice numbers per year: INV-2026-0001, INV-2026-0002, ...
 *
 * The counter lives in the "counters" collection ({ _id: "invoice-2026", seq: 2 }).
 * MongoDB's findAndModify with $inc is atomic, so two invoices issued at the same
 * moment can never receive the same number.
 */
@Component
public class InvoiceNumberGenerator {

    public static final String COUNTERS_COLLECTION = "counters";

    private final MongoTemplate mongoTemplate;
    private final BillingProperties properties;

    public InvoiceNumberGenerator(MongoTemplate mongoTemplate, BillingProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
    }

    public String nextNumber(int year) {
        Query query = new Query(Criteria.where("_id").is("invoice-" + year));
        Update update = new Update().inc("seq", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true).upsert(true);

        Document counter = mongoTemplate.findAndModify(query, update, options, Document.class, COUNTERS_COLLECTION);
        long sequence = counter == null ? 1 : ((Number) counter.get("seq")).longValue();

        return String.format("%s-%d-%04d", properties.invoicePrefix(), year, sequence);
    }
}
