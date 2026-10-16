package com.example.billing.payment;

import com.example.billing.common.ObjectIds;
import com.example.billing.customer.Customer;
import com.example.billing.customer.CustomerRepository;
import com.example.billing.exception.BadRequestException;
import com.example.billing.exception.ResourceNotFoundException;
import com.example.billing.invoice.Invoice;
import com.example.billing.invoice.InvoiceCalculator;
import com.example.billing.invoice.InvoiceRepository;
import com.example.billing.invoice.InvoiceRules;
import com.example.billing.invoice.InvoiceService;
import com.example.billing.invoice.InvoiceStatus;
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
import java.util.stream.Collectors;

/**
 * Business logic for payments. Every change to a payment recalculates the invoice's
 * amount paid, balance due and status (ISSUED / PARTIALLY_PAID / PAID).
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final MongoTemplate mongoTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          InvoiceRepository invoiceRepository,
                          CustomerRepository customerRepository,
                          InvoiceService invoiceService,
                          MongoTemplate mongoTemplate) {
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.customerRepository = customerRepository;
        this.invoiceService = invoiceService;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Records a payment. Rules: the invoice must be ISSUED or PARTIALLY_PAID, the amount cannot
     * exceed the balance due, and the payment date cannot be before the issue date.
     */
    public PaymentResponse create(String invoiceId, PaymentRequest request) {
        Invoice invoice = invoiceService.getExistingInvoice(invoiceId);
        if (!InvoiceRules.canRecordPayment(invoice.getStatus())) {
            String hint = invoice.getStatus() == InvoiceStatus.DRAFT ? " Issue the invoice first."
                    : invoice.getStatus() == InvoiceStatus.PAID ? " It is already fully paid." : "";
            throw new BadRequestException("Payments can only be recorded for ISSUED or PARTIALLY_PAID invoices. "
                    + "This invoice is " + invoice.getStatus() + "." + hint);
        }
        validate(request, invoice, invoice.getBalanceDue());

        Payment payment = new Payment();
        payment.setInvoiceId(invoice.getId());
        payment.setCustomerId(invoice.getCustomerId());
        copyFields(request, payment);
        Instant now = Instant.now();
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);

        Payment saved = paymentRepository.save(payment);
        recalculateInvoice(invoice);
        return toResponse(saved, invoice);
    }

    public List<PaymentResponse> findByInvoice(String invoiceId) {
        Invoice invoice = invoiceService.getExistingInvoice(invoiceId);
        String customerName = customerRepository.findById(invoice.getCustomerId()).map(Customer::getName).orElse(null);
        return paymentRepository.findByInvoiceIdOrderByPaymentDateDescCreatedAtDesc(invoiceId).stream()
                .map(p -> PaymentResponse.from(p, invoice.getInvoiceNumber(), customerName))
                .toList();
    }

    /** Lists payments, newest first. All filters are optional; from/to are inclusive payment dates. */
    public List<PaymentResponse> findAll(PaymentMethod method, String customerId, LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("'to' date cannot be before 'from' date.");
        }
        Query query = new Query();
        if (method != null) {
            query.addCriteria(Criteria.where("method").is(method));
        }
        if (customerId != null && !customerId.isBlank()) {
            ObjectIds.requireValid(customerId, "customer ID");
            query.addCriteria(Criteria.where("customerId").is(customerId));
        }
        if (from != null || to != null) {
            Criteria date = Criteria.where("paymentDate");
            if (from != null) {
                date = date.gte(from);
            }
            if (to != null) {
                date = date.lte(to);
            }
            query.addCriteria(date);
        }
        query.with(Sort.by(Sort.Direction.DESC, "paymentDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        return toResponses(mongoTemplate.find(query, Payment.class));
    }

    public PaymentResponse findById(String id) {
        Payment payment = getExistingPayment(id);
        Invoice invoice = invoiceRepository.findById(payment.getInvoiceId()).orElse(null);
        return toResponse(payment, invoice);
    }

    /** Correcting a payment: the new amount may use the balance due plus this payment's old amount. */
    public PaymentResponse update(String id, PaymentRequest request) {
        Payment payment = getExistingPayment(id);
        Invoice invoice = invoiceService.getExistingInvoice(payment.getInvoiceId());
        BigDecimal available = InvoiceCalculator.money(invoice.getBalanceDue().add(payment.getAmount()));
        validate(request, invoice, available);

        copyFields(request, payment);
        payment.setUpdatedAt(Instant.now());
        Payment saved = paymentRepository.save(payment);
        recalculateInvoice(invoice);
        return toResponse(saved, invoice);
    }

    /** Deleting a payment re-opens the balance (PAID can go back to PARTIALLY_PAID or ISSUED). */
    public void delete(String id) {
        Payment payment = getExistingPayment(id);
        paymentRepository.delete(payment);
        invoiceRepository.findById(payment.getInvoiceId()).ifPresent(this::recalculateInvoice);
    }

    // ------------------------------------------------------------------ helpers

    private void validate(PaymentRequest request, Invoice invoice, BigDecimal maximum) {
        BigDecimal amount = InvoiceCalculator.money(request.amount());
        if (amount.compareTo(maximum) > 0) {
            throw new BadRequestException("Payment of Rs. " + amount + " is more than the balance due (Rs. "
                    + maximum + ") on invoice " + invoice.getInvoiceNumber() + ".");
        }
        if (invoice.getIssueDate() != null && request.paymentDate().isBefore(invoice.getIssueDate())) {
            throw new BadRequestException("Payment date (" + request.paymentDate()
                    + ") cannot be before the invoice issue date (" + invoice.getIssueDate() + ").");
        }
        boolean hasReference = request.reference() != null && !request.reference().isBlank();
        if (request.method() != PaymentMethod.CASH && !hasReference) {
            throw new BadRequestException("A reference (transaction ID, cheque number, ...) is required for "
                    + request.method() + " payments.");
        }
    }

    /** Sum of all payments is the single source of truth for the invoice's paid amount. */
    private void recalculateInvoice(Invoice invoice) {
        BigDecimal paid = paymentRepository.findByInvoiceId(invoice.getId()).stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setAmountPaid(InvoiceCalculator.money(paid));
        invoice.setBalanceDue(InvoiceCalculator.balanceDue(invoice.getTotalAmount(), paid));
        invoice.setStatus(InvoiceCalculator.statusForPayments(invoice.getTotalAmount(), paid));
        invoice.setUpdatedAt(Instant.now());
        invoiceRepository.save(invoice);
    }

    private void copyFields(PaymentRequest request, Payment payment) {
        payment.setAmount(InvoiceCalculator.money(request.amount()));
        payment.setPaymentDate(request.paymentDate());
        payment.setMethod(request.method());
        payment.setReference(request.reference() == null || request.reference().isBlank() ? null : request.reference().trim());
        payment.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
    }

    private Payment getExistingPayment(String id) {
        ObjectIds.requireValid(id, "payment ID");
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    private PaymentResponse toResponse(Payment payment, Invoice invoice) {
        String customerName = customerRepository.findById(payment.getCustomerId()).map(Customer::getName).orElse(null);
        return PaymentResponse.from(payment, invoice != null ? invoice.getInvoiceNumber() : null, customerName);
    }

    /** Converts many payments, loading their invoices and customers with one query each. */
    public List<PaymentResponse> toResponses(List<Payment> payments) {
        Set<String> invoiceIds = new HashSet<>();
        Set<String> customerIds = new HashSet<>();
        payments.forEach(p -> {
            invoiceIds.add(p.getInvoiceId());
            customerIds.add(p.getCustomerId());
        });
        Map<String, Invoice> invoices = invoiceRepository.findAllById(invoiceIds).stream()
                .collect(Collectors.toMap(Invoice::getId, Function.identity()));
        Map<String, Customer> customers = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        return payments.stream()
                .map(p -> {
                    Invoice i = invoices.get(p.getInvoiceId());
                    Customer c = customers.get(p.getCustomerId());
                    return PaymentResponse.from(p, i != null ? i.getInvoiceNumber() : null, c != null ? c.getName() : null);
                })
                .toList();
    }
}
