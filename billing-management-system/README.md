# Billing Management System (Spring Boot + MongoDB)

A web application for billing customers: keep a customer list, create invoices with line items and 13% VAT, issue them with sequential invoice numbers, record payments (cash, bank transfer, cheque, card, eSewa, Khalti), and follow outstanding and overdue amounts on a dashboard. Amounts are in Nepali rupees.

It uses the same setup as the Client Management System: Java 25, Spring Boot 3.5.6, Spring Data MongoDB, and a plain HTML/CSS/JavaScript frontend served by Spring Boot. There is no login, Docker, or cloud account. Everything runs from **IntelliJ IDEA** with no terminal.

**It runs on port 8081**, so you can run it at the same time as the Client Management System (port 8080). It uses its own database, `billing_management`.

---

## Quick start (IntelliJ IDEA)

You already ran the Client Management System, so your Java 25 SDK, Maven settings and MongoDB are ready. The steps are the same:

1. **Make sure MongoDB is running.** In Windows **Services**, **MongoDB Server** should be *Running*. You can also open `http://localhost:27017` in a browser and check for the short "MongoDB over HTTP" message.
2. Unzip `billing-management-system.zip`. In IntelliJ choose **File → Open…**, select the **`pom.xml`** inside it, then choose **Open as Project** and **Trust Project**.
3. **File → Project Structure → Project → SDK:** your JDK 25.
4. Wait for the Maven dependencies to finish loading (progress bar at the bottom). If the code shows red, open the **Maven** tool window and click **Reload All Maven Projects**.
5. Open `src/main/java/com/example/billing/BillingManagementApplication.java` and click the **green Run arrow** next to `main`.
6. When the Run window shows `Connected to MongoDB database 'billing_management'` and `Started BillingManagementApplication`, open **http://localhost:8081**.

**Stop:** red square in the Run window. **Restart:** the Rerun (circular arrow) button, then choose **Stop and Rerun** if asked.

The project includes a ready run configuration, **BillingManagementApplication**, in the toolbar drop-down.

If you open it as a new window while the CMS is open, IntelliJ may ask *This Window* or *New Window*. Either is fine.

### MongoDB connection

`src/main/resources/application.properties`:

```properties
spring.data.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/billing_management}
```

To use another address, either edit that default, or set an environment variable in **Run → Edit Configurations… → BillingManagementApplication → Environment variables** (use **Modify options** if the field is hidden), for example `MONGODB_URI=mongodb://localhost:27017/billing_management`.

Check connectivity at `http://localhost:8081/api/health`. The sidebar dot is green when connected.

### Put your business details on invoices

Open `src/main/resources/static/app.js` and edit the `BUSINESS` block at the top (name, address, phone, email, PAN). Restart the app and reload the page with **Ctrl+F5**.

---

## How billing works

### Invoice life cycle

```
DRAFT ──issue──► ISSUED ──payment──► PARTIALLY_PAID ──payment──► PAID
  │                │
  └────cancel──────┴──► CANCELLED   (only while nothing has been paid)
```

1. **Draft.** Create an invoice for an **active** customer. Add line items (description, quantity, unit price), an optional discount, and the VAT rate (13% by default). Drafts have **no invoice number** and can be edited or deleted freely.
2. **Issue.** Issuing assigns the next number for the issue year (`INV-2026-0001`, `INV-2026-0002`, …) and **locks** the invoice. Numbers come from an atomic MongoDB counter, so they are sequential and never duplicated. An invoice with a total of 0 cannot be issued.
3. **Payments.** Record payments against an issued invoice. The invoice moves to **Partially paid** and then **Paid** automatically. Deleting or correcting a payment recalculates everything, so a paid invoice can go back to partially paid.
4. **Cancel.** A draft, or an issued invoice with no payments, can be cancelled. It is kept for the record. Invoices with payments cannot be cancelled until those payments are deleted.

### Business rules

| Rule | Detail |
|---|---|
| Totals are calculated by the server | Line total = qty × unit price; subtotal; taxable = subtotal − discount; VAT = taxable × rate ÷ 100; total = taxable + VAT. Each step is rounded half-up to the paisa. Amounts are stored as exact decimals (Decimal128). |
| Defaults | Issue date = today, due date = issue date + 15 days, VAT = 13%. Change the defaults in `application.properties` (`billing.*`). |
| Dates | Due date cannot be before the issue date. |
| Discount | Cannot be negative or larger than the subtotal. |
| Editing | Only drafts can be edited or deleted. Issued invoices are locked. |
| Customers | Only **ACTIVE** customers can be invoiced. **INACTIVE** customers keep their history and can still pay existing invoices. A customer with invoices cannot be deleted (409); set them inactive instead. |
| Payments | Only for issued or partially paid invoices. The amount must be > 0 and ≤ the balance due. The date cannot be in the future or before the issue date. A **reference** is required for every method except cash. |
| Overdue | An invoice is overdue when it is issued or partially paid and the due date is before today. |
| Unique values | Customer email (case-insensitive, unique index) and invoice number (unique index). |

### Worked example

Items: 2 × Website design @ Rs. 1,500 and 1 × Domain and hosting @ Rs. 999.99, discount Rs. 100, VAT 13%.

| | Amount |
|---|---|
| Subtotal | Rs. 3,999.99 |
| Discount | − Rs. 100.00 |
| Taxable amount | Rs. 3,899.99 |
| VAT 13% (506.9987 → rounded) | Rs. 507.00 |
| **Total** | **Rs. 4,406.99** |

---

## Using the application

- **Dashboard:**
  - money totals: billed, collected, outstanding, overdue, collected this month
  - invoice counts by status
  - recent invoices, overdue invoices and recent payments
- **Customers:**
  - search by name, email, phone or PAN, and filter by status and type
  - add, edit and delete with confirmation
  - customer page with an account summary (billed, paid, outstanding, overdue) and their invoices
- **Invoices:**
  - overdue section, plus filters by status and customer and a search by invoice number
  - the editor adds and removes line items with a live totals preview, and offers *Save as draft* or *Save and issue*
  - the invoice page is printable (the **Print** button prints only the invoice) and shows payments and history
- **Payments:**
  - list with filters by method, customer and date range, and a running total
  - edit and delete
  - new payments are recorded from an invoice with **Record payment**

---

## REST API

Base path `/api`, JSON bodies, dates as `YYYY-MM-DD`.

| Method | URL | Purpose |
|---|---|---|
| POST | `/api/customers` | Create customer |
| GET | `/api/customers?search=&status=&customerType=` | List / search / filter |
| GET | `/api/customers/{id}` | Get customer |
| PUT | `/api/customers/{id}` | Update customer |
| DELETE | `/api/customers/{id}` | Delete (409 if the customer has invoices) |
| GET | `/api/customers/{id}/summary` | Account summary |
| GET | `/api/customers/{id}/invoices` | Invoices of a customer |
| POST | `/api/invoices` | Create draft invoice |
| GET | `/api/invoices?status=&customerId=&search=` | List / filter (search = invoice number) |
| GET | `/api/invoices/overdue` | Overdue invoices |
| GET | `/api/invoices/{id}` | Get invoice |
| PUT | `/api/invoices/{id}` | Update draft |
| DELETE | `/api/invoices/{id}` | Delete draft (409 otherwise) |
| POST | `/api/invoices/{id}/issue` | Issue: number + lock |
| POST | `/api/invoices/{id}/cancel` | Cancel |
| POST | `/api/invoices/{id}/payments` | Record payment |
| GET | `/api/invoices/{id}/payments` | Payments of an invoice |
| GET | `/api/payments?method=&customerId=&from=&to=` | List / filter payments |
| GET | `/api/payments/{id}` | Get payment |
| PUT | `/api/payments/{id}` | Correct payment |
| DELETE | `/api/payments/{id}` | Delete payment (balance reopens) |
| GET | `/api/dashboard/summary` | Dashboard statistics |
| GET | `/api/health` | Database connectivity |

`api-requests.http` contains ready-to-run examples for IntelliJ's HTTP Client.

### Example: create a draft invoice

`POST http://localhost:8081/api/invoices`

```json
{
  "customerId": "6710f3a2c1d4e85b2a9f0c11",
  "issueDate": "2026-10-16",
  "dueDate": "2026-10-31",
  "items": [
    { "description": "Website design", "quantity": 2, "unitPrice": 1500 },
    { "description": "Domain and hosting (1 year)", "quantity": 1, "unitPrice": 999.99 }
  ],
  "discountAmount": 100,
  "notes": "Thank you for your business"
}
```

Response `201 Created` (shortened):

```json
{
  "id": "6710f4b7c1d4e85b2a9f0c20",
  "invoiceNumber": null,
  "customerName": "Himalayan Traders Pvt. Ltd.",
  "items": [
    { "description": "Website design", "quantity": 2, "unitPrice": 1500.00, "lineTotal": 3000.00 },
    { "description": "Domain and hosting (1 year)", "quantity": 1, "unitPrice": 999.99, "lineTotal": 999.99 }
  ],
  "subtotal": 3999.99,
  "discountAmount": 100.00,
  "taxableAmount": 3899.99,
  "taxRate": 13.00,
  "taxAmount": 507.00,
  "totalAmount": 4406.99,
  "amountPaid": 0.00,
  "balanceDue": 4406.99,
  "status": "DRAFT",
  "overdue": false,
  "canEdit": true, "canIssue": true, "canCancel": true, "canRecordPayment": false, "canDelete": true
}
```

### Example: issue, then pay

`POST /api/invoices/6710f4b7c1d4e85b2a9f0c20/issue` returns the invoice with `"invoiceNumber": "INV-2026-0001"` and `"status": "ISSUED"`.

`POST /api/invoices/6710f4b7c1d4e85b2a9f0c20/payments`

```json
{ "amount": 1000, "paymentDate": "2026-10-16", "method": "ESEWA", "reference": "ESW-20261016-001" }
```

This returns `201 Created`. The invoice is now `PARTIALLY_PAID`, with `amountPaid` 1000.00 and `balanceDue` 3406.99.

Paying more than the balance returns `400`:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Payment of Rs. 5000.00 is more than the balance due (Rs. 3406.99) on invoice INV-2026-0001.",
  "path": "/api/invoices/6710f4b7c1d4e85b2a9f0c20/payments"
}
```

Editing an issued invoice returns `400` with a message that only DRAFT invoices can be changed. Deleting a customer who has invoices returns `409` with an explanation.

### Error codes

| Code | When |
|---|---|
| 400 | Validation failed, bad ID format, unknown enum value, bad date, broken business rule |
| 404 | Record not found |
| 409 | Duplicate email, deleting a customer with invoices, deleting a non-draft invoice |
| 503 | MongoDB not reachable |
| 500 | Unexpected error (details only in the Run console) |

---

## Automated tests

| Test class | Covers | Needs MongoDB |
|---|---|---|
| `InvoiceCalculatorTest` | Line totals, discount, VAT rounding, balance, status after payments | No |
| `InvoiceRulesTest` | Edit/issue/cancel/payment permissions per status, overdue logic | No |
| `CustomerApiIntegrationTest` | Customer CRUD, duplicate emails, PAN validation, search, deletion rule, account summary | Yes |
| `InvoiceApiIntegrationTest` | Server-side totals, defaults, validation, inactive customers, draft editing, sequential numbering, locking, cancelling, overdue, filters | Yes |
| `PaymentApiIntegrationTest` | Partial/full payment, overpayment, references, dates, correcting and deleting payments, filters | Yes |
| `DashboardIntegrationTest` | Dashboard counts and amounts | Yes |

**Your data is safe.** Integration tests use a separate database, **`billing_management_test`**, and empty only that database's collections before each test. A safety check refuses to run against any other database. If MongoDB is not running, these tests are reported as skipped.

Run them by right-clicking `src/test/java` → **Run 'All Tests'**, or with **Maven tool window → Lifecycle → test**.

---

## Troubleshooting

**Red code / "Cannot resolve symbol".** Maven dependencies have not loaded. Use **Maven tool window → Reload All Maven Projects**. If that doesn't help, use **File → Invalidate Caches… → Invalidate and Restart**.

**"release version 25 not supported".** Check **Project Structure → Project → SDK** is JDK 25. Also check that **Settings → Build Tools → Maven → Importing → JDK for importer** and **Maven → Runner → JRE** use the project JDK. IntelliJ must be 2025.2 or newer.

**"COULD NOT CONNECT TO MONGODB", red banner, or HTTP 503.** Start MongoDB (**Services → MongoDB Server → Start**), then **Stop and Rerun** the application.

**"Port 8081 was already in use".** This application is probably already running in another Run tab, so stop it first. Otherwise change `server.port` in `application.properties`, or set `SERVER_PORT=8082` in the run configuration's environment variables.

**The Print button prints the whole page.** Use the **Print** button on the invoice page, not the browser menu on another page. In the print dialog, turn off *Headers and footers* for a cleaner result.

**Frontend changes do not appear.** Restart the application and press **Ctrl+F5** in the browser.

---

## Project structure

```
billing-management-system/
├── pom.xml
├── api-requests.http
├── .run/BillingManagementApplication.run.xml
└── src/
    ├── main/java/com/example/billing/
    │   ├── BillingManagementApplication.java
    │   ├── customer/     Customer document, repository, service, controller, DTOs
    │   ├── invoice/      Invoice + embedded items, calculator, rules, number generator, service, controller, DTOs
    │   ├── payment/      Payment document, repository, service, controller, DTOs
    │   ├── dashboard/    Statistics service, controller, DTO
    │   ├── common/       MongoDB ID validation, health endpoint
    │   ├── exception/    Exceptions and the global JSON error handler
    │   └── config/       Billing settings, MongoDB client settings, startup check and indexes
    ├── main/resources/
    │   ├── application.properties
    │   └── static/       index.html, style.css, app.js
    └── test/java/com/example/billing/   unit and integration tests
```

Request flow: **Controller → Service → Repository → MongoDB**. All business rules live in services and in the plain-Java `InvoiceCalculator` and `InvoiceRules`. Controllers return DTOs, never database documents.
