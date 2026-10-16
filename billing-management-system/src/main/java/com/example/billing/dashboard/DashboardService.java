package com.example.billing.dashboard;

import com.example.billing.customer.CustomerRepository;
import com.example.billing.customer.CustomerStatus;
import com.example.billing.invoice.Invoice;
import com.example.billing.invoice.InvoiceCalculator;
import com.example.billing.invoice.InvoiceRepository;
import com.example.billing.invoice.InvoiceResponse;
import com.example.billing.invoice.InvoiceRules;
import com.example.billing.invoice.InvoiceService;
import com.example.billing.invoice.InvoiceStatus;
import com.example.billing.payment.Payment;
import com.example.billing.payment.PaymentRepository;
import com.example.billing.payment.PaymentService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Dashboard statistics, calculated from the database on every request (nothing is hard-coded). */
@Service
public class DashboardService {

    private static final int LIST_LIMIT = 5;

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    public DashboardService(CustomerRepository customerRepository,
                            InvoiceRepository invoiceRepository,
                            PaymentRepository paymentRepository,
                            InvoiceService invoiceService,
                            PaymentService paymentService) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
    }

    public DashboardSummaryResponse getSummary() {
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);

        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        long overdueCount = 0;
        for (Invoice invoice : invoiceRepository.findByStatusIn(InvoiceRules.BILLED_STATUSES)) {
            billed = billed.add(invoice.getTotalAmount());
            if (InvoiceRules.OPEN_STATUSES.contains(invoice.getStatus())) {
                outstanding = outstanding.add(invoice.getBalanceDue());
            }
            if (InvoiceRules.isOverdue(invoice.getDueDate(), invoice.getStatus(), today)) {
                overdueCount++;
                overdueAmount = overdueAmount.add(invoice.getBalanceDue());
            }
        }

        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal collectedThisMonth = BigDecimal.ZERO;
        for (Payment payment : paymentRepository.findAll()) {
            collected = collected.add(payment.getAmount());
            if (YearMonth.from(payment.getPaymentDate()).equals(thisMonth)) {
                collectedThisMonth = collectedThisMonth.add(payment.getAmount());
            }
        }

        List<InvoiceResponse> recentInvoices = invoiceService.toResponses(invoiceRepository.findTop5ByOrderByCreatedAtDesc());
        List<InvoiceResponse> overdueList = invoiceService.findOverdue().stream().limit(LIST_LIMIT).toList();

        return new DashboardSummaryResponse(
                customerRepository.count(),
                customerRepository.countByStatus(CustomerStatus.ACTIVE),
                invoiceRepository.count(),
                invoiceRepository.countByStatus(InvoiceStatus.DRAFT),
                invoiceRepository.countByStatus(InvoiceStatus.ISSUED),
                invoiceRepository.countByStatus(InvoiceStatus.PARTIALLY_PAID),
                invoiceRepository.countByStatus(InvoiceStatus.PAID),
                invoiceRepository.countByStatus(InvoiceStatus.CANCELLED),
                overdueCount,
                InvoiceCalculator.money(billed),
                InvoiceCalculator.money(collected),
                InvoiceCalculator.money(outstanding),
                InvoiceCalculator.money(overdueAmount),
                InvoiceCalculator.money(collectedThisMonth),
                recentInvoices,
                overdueList,
                paymentService.toResponses(paymentRepository.findTop5ByOrderByPaymentDateDescCreatedAtDesc()));
    }
}
