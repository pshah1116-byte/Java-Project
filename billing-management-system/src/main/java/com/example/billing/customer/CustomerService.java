package com.example.billing.customer;

import com.example.billing.common.ObjectIds;
import com.example.billing.exception.ConflictException;
import com.example.billing.exception.ResourceNotFoundException;
import com.example.billing.invoice.Invoice;
import com.example.billing.invoice.InvoiceCalculator;
import com.example.billing.invoice.InvoiceRepository;
import com.example.billing.invoice.InvoiceRules;
import com.example.billing.invoice.InvoiceStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Business logic for customers. */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final MongoTemplate mongoTemplate;

    public CustomerService(CustomerRepository customerRepository,
                           InvoiceRepository invoiceRepository,
                           MongoTemplate mongoTemplate) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /** A customer created without a status becomes ACTIVE. */
    public CustomerResponse create(CustomerRequest request) {
        String email = normalizeEmail(request.email());
        ensureEmailIsFree(email, null);

        Customer customer = new Customer();
        copyFields(request, email, customer);
        customer.setStatus(request.status() != null ? request.status() : CustomerStatus.ACTIVE);
        Instant now = Instant.now();
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);
        return CustomerResponse.from(saveWithUniqueEmail(customer));
    }

    /**
     * Lists customers, newest first. "search" matches name, email, phone or PAN
     * (case-insensitive; with several words every word must match).
     */
    public List<CustomerResponse> findAll(String search, CustomerStatus status, CustomerType customerType) {
        Query query = new Query();
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status));
        }
        if (customerType != null) {
            query.addCriteria(Criteria.where("customerType").is(customerType));
        }
        if (search != null && !search.isBlank()) {
            List<Criteria> words = new ArrayList<>();
            for (String word : search.trim().split("\\s+")) {
                Pattern p = Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE);
                words.add(new Criteria().orOperator(
                        Criteria.where("name").regex(p),
                        Criteria.where("email").regex(p),
                        Criteria.where("phone").regex(p),
                        Criteria.where("panNumber").regex(p)));
            }
            query.addCriteria(new Criteria().andOperator(words));
        }
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(query, Customer.class).stream().map(CustomerResponse::from).toList();
    }

    public CustomerResponse findById(String id) {
        return CustomerResponse.from(getExistingCustomer(id));
    }

    public CustomerResponse update(String id, CustomerRequest request) {
        Customer customer = getExistingCustomer(id);
        String email = normalizeEmail(request.email());
        ensureEmailIsFree(email, customer.getId());

        copyFields(request, email, customer);
        if (request.status() != null) {
            customer.setStatus(request.status());
        }
        customer.setUpdatedAt(Instant.now());
        return CustomerResponse.from(saveWithUniqueEmail(customer));
    }

    /** A customer with invoices cannot be deleted (their billing history must be kept). */
    public void delete(String id) {
        Customer customer = getExistingCustomer(id);
        long invoices = invoiceRepository.countByCustomerId(id);
        if (invoices > 0) {
            throw new ConflictException("Cannot delete customer '" + customer.getName() + "' because they have "
                    + invoices + " invoice(s). Billing history must be kept; set the customer to INACTIVE instead.");
        }
        customerRepository.delete(customer);
    }

    /** Account statement in numbers: billed, paid, outstanding and overdue. */
    public CustomerSummaryResponse getSummary(String id) {
        Customer customer = getExistingCustomer(id);
        List<Invoice> invoices = invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(id);
        LocalDate today = LocalDate.now();

        long drafts = 0;
        long unpaid = 0;
        long paid = 0;
        long overdue = 0;
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;

        for (Invoice invoice : invoices) {
            InvoiceStatus status = invoice.getStatus();
            if (status == InvoiceStatus.DRAFT) {
                drafts++;
            }
            if (status == InvoiceStatus.PAID) {
                paid++;
            }
            if (InvoiceRules.OPEN_STATUSES.contains(status)) {
                unpaid++;
                outstanding = outstanding.add(invoice.getBalanceDue());
            }
            if (InvoiceRules.BILLED_STATUSES.contains(status)) {
                billed = billed.add(invoice.getTotalAmount());
                received = received.add(invoice.getAmountPaid());
            }
            if (InvoiceRules.isOverdue(invoice.getDueDate(), status, today)) {
                overdue++;
                overdueAmount = overdueAmount.add(invoice.getBalanceDue());
            }
        }

        return new CustomerSummaryResponse(CustomerResponse.from(customer), invoices.size(), drafts, unpaid, paid,
                overdue, InvoiceCalculator.money(billed), InvoiceCalculator.money(received),
                InvoiceCalculator.money(outstanding), InvoiceCalculator.money(overdueAmount));
    }

    public Customer getExistingCustomer(String id) {
        ObjectIds.requireValid(id, "customer ID");
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    // ------------------------------------------------------------------ helpers

    private void copyFields(CustomerRequest request, String email, Customer customer) {
        customer.setName(request.name().trim());
        customer.setEmail(email);
        customer.setPhone(request.phone().trim());
        customer.setAddress(blankToNull(request.address()));
        customer.setPanNumber(blankToNull(request.panNumber()));
        customer.setCustomerType(request.customerType());
        customer.setNotes(blankToNull(request.notes()));
    }

    private void ensureEmailIsFree(String email, String currentId) {
        customerRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(currentId)) {
                throw new ConflictException("A customer with email '" + email + "' already exists.");
            }
        });
    }

    private Customer saveWithUniqueEmail(Customer customer) {
        try {
            return customerRepository.save(customer);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A customer with email '" + customer.getEmail() + "' already exists.");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
