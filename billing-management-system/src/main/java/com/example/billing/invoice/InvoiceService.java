package com.example.billing.invoice;

import com.example.billing.common.ObjectIds;
import com.example.billing.config.BillingProperties;
import com.example.billing.customer.Customer;
import com.example.billing.customer.CustomerRepository;
import com.example.billing.customer.CustomerStatus;
import com.example.billing.exception.BadRequestException;
import com.example.billing.exception.ConflictException;
import com.example.billing.exception.ResourceNotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Business logic for invoices: totals, numbering, the invoice life cycle and overdue invoices. */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceNumberGenerator numberGenerator;
    private final BillingProperties properties;
    private final MongoTemplate mongoTemplate;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          CustomerRepository customerRepository,
                          InvoiceNumberGenerator numberGenerator,
                          BillingProperties properties,
                          MongoTemplate mongoTemplate) {
        this.invoiceRepository = invoiceRepository;
        this.customerRepository = customerRepository;
        this.numberGenerator = numberGenerator;
        this.properties = properties;
        this.mongoTemplate = mongoTemplate;
    }

    // ------------------------------------------------------------------ create / read

    /** New invoices start as DRAFT. The server calculates every amount. */
    public InvoiceResponse create(InvoiceRequest request) {
        Customer customer = findBillableCustomer(request.customerId());

        Invoice invoice = new Invoice();
        invoice.setCustomerId(customer.getId());
        invoice.setStatus(InvoiceStatus.DRAFT);
        applyRequest(request, invoice);

        Instant now = Instant.now();
        invoice.setCreatedAt(now);
        invoice.setUpdatedAt(now);

        return toResponse(invoiceRepository.save(invoice), customer.getName());
    }

    /**
     * Lists invoices, newest first. Filters are optional and can be combined.
     * "search" matches the invoice number, e.g. "2026-00".
     */
    public List<InvoiceResponse> findAll(InvoiceStatus status, String customerId, String search) {
        Query query = new Query();
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status));
        }
        if (customerId != null && !customerId.isBlank()) {
            ObjectIds.requireValid(customerId, "customer ID");
            query.addCriteria(Criteria.where("customerId").is(customerId));
        }
        if (search != null && !search.isBlank()) {
            Pattern pattern = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            query.addCriteria(Criteria.where("invoiceNumber").regex(pattern));
        }
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return toResponses(mongoTemplate.find(query, Invoice.class));
    }

    public InvoiceResponse findById(String id) {
        Invoice invoice = getExistingInvoice(id);
        return toResponse(invoice, customerNameOf(invoice.getCustomerId()));
    }

    /** GET /api/customers/{customerId}/invoices - the customer must exist. */
    public List<InvoiceResponse> findByCustomer(String customerId) {
        ObjectIds.requireValid(customerId, "customer ID");
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        LocalDate today = LocalDate.now();
        return invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(i -> InvoiceResponse.from(i, customer.getName(), today))
                .toList();
    }

    /** ISSUED or PARTIALLY_PAID invoices whose due date has passed, oldest first. */
    public List<InvoiceResponse> findOverdue() {
        return toResponses(invoiceRepository.findByStatusInAndDueDateBeforeOrderByDueDateAsc(
                InvoiceRules.OPEN_STATUSES, LocalDate.now()));
    }

    // ------------------------------------------------------------------ change

    /** Only DRAFT invoices can be edited. Issued invoices are locked. */
    public InvoiceResponse update(String id, InvoiceRequest request) {
        Invoice invoice = getExistingInvoice(id);
        if (!InvoiceRules.canEdit(invoice.getStatus())) {
            throw new BadRequestException("Invoice " + label(invoice) + " is " + invoice.getStatus()
                    + " and can no longer be edited. Only DRAFT invoices can be changed; "
                    + "cancel it and create a new invoice instead.");
        }
        Customer customer = findBillableCustomer(request.customerId());
        invoice.setCustomerId(customer.getId());
        applyRequest(request, invoice);
        invoice.setUpdatedAt(Instant.now());
        return toResponse(invoiceRepository.save(invoice), customer.getName());
    }

    /** Only DRAFT invoices can be deleted. Issued invoices must be cancelled instead. */
    public void delete(String id) {
        Invoice invoice = getExistingInvoice(id);
        if (!InvoiceRules.canDelete(invoice.getStatus())) {
            throw new ConflictException("Invoice " + label(invoice) + " is " + invoice.getStatus()
                    + " and cannot be deleted. Only DRAFT invoices can be deleted; cancel issued invoices instead.");
        }
        invoiceRepository.delete(invoice);
    }

    /**
     * DRAFT -> ISSUED. Assigns the next invoice number and locks the invoice.
     * The customer must be ACTIVE and the total must be greater than zero.
     */
    public InvoiceResponse issue(String id) {
        Invoice invoice = getExistingInvoice(id);
        if (!InvoiceRules.canIssue(invoice.getStatus())) {
            throw new BadRequestException("Only DRAFT invoices can be issued. Invoice " + label(invoice)
                    + " is " + invoice.getStatus() + ".");
        }
        if (invoice.getTotalAmount() == null || invoice.getTotalAmount().signum() <= 0) {
            throw new BadRequestException("An invoice with a total of 0 cannot be issued. Add items with a price first.");
        }
        Customer customer = findBillableCustomer(invoice.getCustomerId());

        invoice.setInvoiceNumber(numberGenerator.nextNumber(invoice.getIssueDate().getYear()));
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setAmountPaid(InvoiceCalculator.money(BigDecimal.ZERO));
        invoice.setBalanceDue(invoice.getTotalAmount());
        Instant now = Instant.now();
        invoice.setIssuedAt(now);
        invoice.setUpdatedAt(now);

        return toResponse(invoiceRepository.save(invoice), customer.getName());
    }

    /** DRAFT or unpaid ISSUED -> CANCELLED. Invoices with payments cannot be cancelled. */
    public InvoiceResponse cancel(String id) {
        Invoice invoice = getExistingInvoice(id);
        if (!InvoiceRules.canCancel(invoice.getStatus(), invoice.getAmountPaid())) {
            String reason = switch (invoice.getStatus()) {
                case CANCELLED -> "it is already cancelled.";
                case PAID, PARTIALLY_PAID -> "payments of Rs. " + invoice.getAmountPaid()
                        + " have been recorded. Delete those payments first.";
                default -> "its status is " + invoice.getStatus() + ".";
            };
            throw new BadRequestException("Invoice " + label(invoice) + " cannot be cancelled because " + reason);
        }
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setBalanceDue(InvoiceCalculator.money(BigDecimal.ZERO));
        Instant now = Instant.now();
        invoice.setCancelledAt(now);
        invoice.setUpdatedAt(now);
        return toResponse(invoiceRepository.save(invoice), customerNameOf(invoice.getCustomerId()));
    }

    // ------------------------------------------------------------------ shared helpers

    /** Loads an invoice or throws 400 (bad ID format) / 404 (not found). Used by payments too. */
    public Invoice getExistingInvoice(String id) {
        ObjectIds.requireValid(id, "invoice ID");
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
    }

    /** Copies the request into the invoice, applies defaults, validates dates and discount, and calculates totals. */
    private void applyRequest(InvoiceRequest request, Invoice invoice) {
        LocalDate issueDate = request.issueDate() != null ? request.issueDate() : LocalDate.now();
        LocalDate dueDate = request.dueDate() != null
                ? request.dueDate()
                : issueDate.plusDays(properties.defaultPaymentTermsDays());
        if (dueDate.isBefore(issueDate)) {
            throw new BadRequestException("Due date (" + dueDate + ") cannot be earlier than the issue date ("
                    + issueDate + ").");
        }

        BigDecimal taxRate = request.taxRate() != null ? request.taxRate() : properties.defaultTaxRate();

        List<InvoiceItem> items = request.items().stream()
                .map(i -> new InvoiceItem(
                        i.description().trim(),
                        i.quantity(),
                        InvoiceCalculator.money(i.unitPrice()),
                        InvoiceCalculator.lineTotal(i.quantity(), i.unitPrice())))
                .toList();
        List<InvoiceCalculator.Line> lines = items.stream()
                .map(i -> new InvoiceCalculator.Line(i.getQuantity(), i.getUnitPrice()))
                .toList();
        InvoiceCalculator.Totals totals = InvoiceCalculator.calculate(lines, request.discountAmount(), taxRate);

        if (totals.discountAmount().compareTo(totals.subtotal()) > 0) {
            throw new BadRequestException("Discount (Rs. " + totals.discountAmount()
                    + ") cannot be more than the subtotal (Rs. " + totals.subtotal() + ").");
        }

        invoice.setItems(new java.util.ArrayList<>(items));
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.setSubtotal(totals.subtotal());
        invoice.setDiscountAmount(totals.discountAmount());
        invoice.setTaxableAmount(totals.taxableAmount());
        invoice.setTaxRate(totals.taxRate());
        invoice.setTaxAmount(totals.taxAmount());
        invoice.setTotalAmount(totals.totalAmount());
        invoice.setAmountPaid(InvoiceCalculator.money(BigDecimal.ZERO));
        invoice.setBalanceDue(totals.totalAmount());
        invoice.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
    }

    /** The customer must exist (400 otherwise) and be ACTIVE to receive invoices. */
    private Customer findBillableCustomer(String customerId) {
        ObjectIds.requireValid(customerId, "customer ID");
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BadRequestException(
                        "Customer with id '" + customerId + "' does not exist. Choose an existing customer."));
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BadRequestException("Customer '" + customer.getName() + "' is " + customer.getStatus()
                    + ". Invoices can only be created or issued for ACTIVE customers.");
        }
        return customer;
    }

    private static String label(Invoice invoice) {
        return invoice.getInvoiceNumber() != null ? invoice.getInvoiceNumber() : "(draft)";
    }

    private String customerNameOf(String customerId) {
        return customerRepository.findById(customerId).map(Customer::getName).orElse(null);
    }

    private InvoiceResponse toResponse(Invoice invoice, String customerName) {
        return InvoiceResponse.from(invoice, customerName, LocalDate.now());
    }

    /** Converts many invoices, loading all their customers in one query. */
    public List<InvoiceResponse> toResponses(List<Invoice> invoices) {
        Set<String> customerIds = new HashSet<>();
        invoices.forEach(i -> customerIds.add(i.getCustomerId()));
        Map<String, Customer> customers = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        LocalDate today = LocalDate.now();
        return invoices.stream()
                .map(i -> {
                    Customer c = customers.get(i.getCustomerId());
                    return InvoiceResponse.from(i, c != null ? c.getName() : null, today);
                })
                .toList();
    }
}
