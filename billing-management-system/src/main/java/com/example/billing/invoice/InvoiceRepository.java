package com.example.billing.invoice;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface InvoiceRepository extends MongoRepository<Invoice, String> {

    List<Invoice> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    long countByCustomerId(String customerId);

    long countByStatus(InvoiceStatus status);

    List<Invoice> findByStatusIn(Collection<InvoiceStatus> statuses);

    /** Overdue = open status and due date before today. Oldest due date first. */
    List<Invoice> findByStatusInAndDueDateBeforeOrderByDueDateAsc(Collection<InvoiceStatus> statuses, LocalDate today);

    List<Invoice> findTop5ByOrderByCreatedAtDesc();
}
