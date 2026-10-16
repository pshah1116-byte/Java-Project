package com.example.billing.payment;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    public ResponseEntity<PaymentResponse> create(@PathVariable("invoiceId") String invoiceId,
                                                  @Valid @RequestBody PaymentRequest request) {
        PaymentResponse created = paymentService.create(invoiceId, request);
        return ResponseEntity.created(URI.create("/api/payments/" + created.id())).body(created);
    }

    @GetMapping("/invoices/{invoiceId}/payments")
    public List<PaymentResponse> findByInvoice(@PathVariable("invoiceId") String invoiceId) {
        return paymentService.findByInvoice(invoiceId);
    }

    /** Example: GET /api/payments?method=ESEWA&from=2026-10-01&to=2026-10-31 */
    @GetMapping("/payments")
    public List<PaymentResponse> findAll(
            @RequestParam(name = "method", required = false) PaymentMethod method,
            @RequestParam(name = "customerId", required = false) String customerId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return paymentService.findAll(method, customerId, from, to);
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse findById(@PathVariable("id") String id) {
        return paymentService.findById(id);
    }

    @PutMapping("/payments/{id}")
    public PaymentResponse update(@PathVariable("id") String id, @Valid @RequestBody PaymentRequest request) {
        return paymentService.update(id, request);
    }

    @DeleteMapping("/payments/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        paymentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
