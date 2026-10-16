package com.example.billing.payment;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PaymentRepository extends MongoRepository<Payment, String> {

    /** Payments of one invoice, newest first. */
    List<Payment> findByInvoiceIdOrderByPaymentDateDescCreatedAtDesc(String invoiceId);

    List<Payment> findByInvoiceId(String invoiceId);

    List<Payment> findTop5ByOrderByPaymentDateDescCreatedAtDesc();
}
