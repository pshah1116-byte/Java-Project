package com.example.billing.invoice;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Invoice endpoints. Fixed paths such as /api/invoices/overdue always win over
 * /api/invoices/{id}, so "overdue" is never treated as an ID.
 */
@RestController
@RequestMapping("/api")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping("/invoices")
    public ResponseEntity<InvoiceResponse> create(@Valid @RequestBody InvoiceRequest request) {
        InvoiceResponse created = invoiceService.create(request);
        return ResponseEntity.created(URI.create("/api/invoices/" + created.id())).body(created);
    }

    /** Example: GET /api/invoices?status=ISSUED&customerId=...&search=2026 */
    @GetMapping("/invoices")
    public List<InvoiceResponse> findAll(@RequestParam(name = "status", required = false) InvoiceStatus status,
                                         @RequestParam(name = "customerId", required = false) String customerId,
                                         @RequestParam(name = "search", required = false) String search) {
        return invoiceService.findAll(status, customerId, search);
    }

    @GetMapping("/invoices/overdue")
    public List<InvoiceResponse> findOverdue() {
        return invoiceService.findOverdue();
    }

    @GetMapping("/invoices/{id}")
    public InvoiceResponse findById(@PathVariable("id") String id) {
        return invoiceService.findById(id);
    }

    @PutMapping("/invoices/{id}")
    public InvoiceResponse update(@PathVariable("id") String id, @Valid @RequestBody InvoiceRequest request) {
        return invoiceService.update(id, request);
    }

    @DeleteMapping("/invoices/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        invoiceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invoices/{id}/issue")
    public InvoiceResponse issue(@PathVariable("id") String id) {
        return invoiceService.issue(id);
    }

    @PostMapping("/invoices/{id}/cancel")
    public InvoiceResponse cancel(@PathVariable("id") String id) {
        return invoiceService.cancel(id);
    }

    @GetMapping("/customers/{customerId}/invoices")
    public List<InvoiceResponse> findByCustomer(@PathVariable("customerId") String customerId) {
        return invoiceService.findByCustomer(customerId);
    }
}
