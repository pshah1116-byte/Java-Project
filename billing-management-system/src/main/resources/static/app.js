/*
 * BillDesk - frontend for the Billing Management System.
 * Plain JavaScript. Every screen calls the real Spring Boot REST API under /api.
 */
'use strict';

/* ======================================================================
   Your business details (printed on invoices). Change these to your own.
   ====================================================================== */

const BUSINESS = {
    name: 'Your Business Name',
    address: 'Kathmandu, Nepal',
    phone: '+977 1 0000000',
    email: 'billing@yourbusiness.com',
    pan: '000000000'
};

/* ======================================================================
   Constants
   ====================================================================== */

const CUSTOMER_STATUSES = ['ACTIVE', 'INACTIVE'];
const CUSTOMER_TYPES = ['INDIVIDUAL', 'BUSINESS'];
const INVOICE_STATUSES = ['DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED'];
const PAYMENT_METHODS = ['CASH', 'BANK_TRANSFER', 'CHEQUE', 'CARD', 'ESEWA', 'KHALTI'];
const DEFAULT_TAX_RATE = 13;
const DEFAULT_TERMS_DAYS = 15;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_PATTERN = /^\+?[0-9 ()\-]{7,20}$/;
const PAN_PATTERN = /^[0-9]{9}$/;
const MONEY_PATTERN = /^\d+(\.\d{1,2})?$/;

const app = document.getElementById('app');

const customerFilters = { search: '', status: '', customerType: '' };
const invoiceFilters = { status: '', customerId: '', search: '' };
const paymentFilters = { method: '', customerId: '', from: '', to: '' };

/* ======================================================================
   API helper
   ====================================================================== */

class ApiError extends Error {
    constructor(message, status, fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors || {};
    }
}

async function api(method, path, body) {
    const options = { method, headers: { Accept: 'application/json' } };
    if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body);
    }
    let response;
    try {
        response = await fetch(path, options);
    } catch (networkError) {
        throw new ApiError('Cannot reach the server. Is the application running in IntelliJ IDEA?', 0);
    }
    if (response.status === 204) return null;

    const text = await response.text();
    let data = null;
    if (text) {
        try { data = JSON.parse(text); } catch (parseError) { data = null; }
    }
    if (!response.ok) {
        if (response.status === 503) setDbStatus(false);
        const message = (data && data.message) || `Request failed (HTTP ${response.status}).`;
        throw new ApiError(message, response.status, data && data.fieldErrors);
    }
    return data;
}

function query(params) {
    const qs = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && String(value).trim() !== '') {
            qs.set(key, String(value).trim());
        }
    });
    const s = qs.toString();
    return s ? `?${s}` : '';
}

/* ======================================================================
   Formatting
   ====================================================================== */

function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function label(enumValue) {
    if (!enumValue) return '';
    if (enumValue === 'ESEWA') return 'eSewa';
    const text = String(enumValue).replace(/_/g, ' ').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
}

function badge(value) {
    return value ? `<span class="badge ${esc(value)}">${esc(label(value))}</span>` : '';
}

function overdueBadge(days) {
    return `<span class="badge overdue">Overdue${days ? ` ${esc(days)}d` : ''}</span>`;
}

/** Nepali-style grouping, e.g. Rs. 1,23,456.50 */
function fmtMoney(amount) {
    if (amount === null || amount === undefined || amount === '') return '—';
    return 'Rs. ' + Number(amount).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function fmtDate(isoDate) {
    if (!isoDate) return '—';
    const [y, m, d] = isoDate.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

function fmtDateTime(isoInstant) {
    if (!isoInstant) return '—';
    return new Date(isoInstant).toLocaleString(undefined, {
        day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
    });
}

function todayISO() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function addDaysISO(iso, days) {
    const [y, m, d] = iso.split('-').map(Number);
    const date = new Date(Date.UTC(y, m - 1, d + days));
    return date.toISOString().slice(0, 10);
}

function invoiceNo(inv) {
    return inv.invoiceNumber || 'Draft';
}

function options(values, selected, placeholder) {
    let html = placeholder !== undefined ? `<option value="">${esc(placeholder)}</option>` : '';
    values.forEach(v => {
        html += `<option value="${esc(v)}" ${v === selected ? 'selected' : ''}>${esc(label(v))}</option>`;
    });
    return html;
}

/* ======================================================================
   UI states, toasts, confirmation, modal
   ====================================================================== */

function loadingHTML(text) {
    return `<div class="loading"><span class="spinner"></span>${esc(text || 'Loading…')}</div>`;
}

function emptyHTML(title, text, actionHtml) {
    return `<div class="empty"><strong>${esc(title)}</strong>${esc(text || '')}${actionHtml ? `<div>${actionHtml}</div>` : ''}</div>`;
}

function errorHTML(message) {
    return `<div class="error-box">${esc(message)}</div>`;
}

function toast(message, type) {
    const el = document.createElement('div');
    el.className = `toast ${type === 'error' ? 'error' : ''}`;
    el.textContent = message;
    document.getElementById('toasts').appendChild(el);
    setTimeout(() => el.remove(), type === 'error' ? 7000 : 3500);
}

function confirmAction(message, okLabel, danger) {
    return new Promise(resolve => {
        const overlay = document.getElementById('confirm');
        const okBtn = document.getElementById('confirmOk');
        const cancelBtn = document.getElementById('confirmCancel');
        document.getElementById('confirmMessage').textContent = message;
        okBtn.textContent = okLabel || 'Delete';
        okBtn.className = `btn ${danger === false ? '' : 'btn-danger'}`;
        overlay.classList.remove('hidden');
        okBtn.focus();
        const finish = result => {
            overlay.classList.add('hidden');
            okBtn.removeEventListener('click', onOk);
            cancelBtn.removeEventListener('click', onCancel);
            resolve(result);
        };
        const onOk = () => finish(true);
        const onCancel = () => finish(false);
        okBtn.addEventListener('click', onOk);
        cancelBtn.addEventListener('click', onCancel);
    });
}

function openModal(title, html) {
    document.getElementById('modalTitle').textContent = title;
    const body = document.getElementById('modalBody');
    body.innerHTML = html;
    document.getElementById('modal').classList.remove('hidden');
    const first = body.querySelector('input, select, textarea');
    if (first) first.focus();
    return body;
}

function closeModal() {
    document.getElementById('modal').classList.add('hidden');
    document.getElementById('modalBody').innerHTML = '';
}

document.querySelectorAll('[data-close-modal]').forEach(btn => btn.addEventListener('click', closeModal));
document.getElementById('modal').addEventListener('click', e => { if (e.target.id === 'modal') closeModal(); });
document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (!document.getElementById('confirm').classList.contains('hidden')) {
        document.getElementById('confirmCancel').click();
    } else {
        closeModal();
    }
});

/* ======================================================================
   Database status
   ====================================================================== */

function setDbStatus(up) {
    const foot = document.getElementById('dbStatus');
    foot.querySelector('.dot').className = `dot ${up ? 'up' : 'down'}`;
    foot.querySelector('.dot-label').textContent = up ? 'MongoDB connected' : 'MongoDB not connected';
    document.getElementById('dbBanner').classList.toggle('hidden', up);
}

async function checkHealth() {
    try {
        const response = await fetch('/api/health');
        setDbStatus(response.ok);
    } catch (e) {
        setDbStatus(false);
    }
}

/* ======================================================================
   Form helpers
   ====================================================================== */

function field(name, text, control, opts) {
    const o = opts || {};
    return `
        <div class="field ${o.full ? 'full' : ''}" data-field="${name}">
            <label for="f-${name}">${esc(text)}${o.required ? ' <span class="req">*</span>' : ''}</label>
            ${control}
            ${o.hint ? `<span class="hint">${esc(o.hint)}</span>` : ''}
            <span class="error"></span>
        </div>`;
}

function input(name, value, attrs) {
    return `<input id="f-${name}" name="${name}" value="${esc(value)}" ${attrs || ''}>`;
}

function textarea(name, value, attrs) {
    return `<textarea id="f-${name}" name="${name}" ${attrs || ''}>${esc(value)}</textarea>`;
}

function select(name, html, attrs) {
    return `<select id="f-${name}" name="${name}" ${attrs || ''}>${html}</select>`;
}

function readForm(form) {
    const data = {};
    new FormData(form).forEach((value, key) => {
        const v = typeof value === 'string' ? value.trim() : value;
        data[key] = v === '' ? null : v;
    });
    return data;
}

function clearFormErrors(form) {
    form.querySelectorAll('[data-field].invalid').forEach(f => f.classList.remove('invalid'));
    form.querySelectorAll('[data-field] .error, .field-error').forEach(e => { e.textContent = ''; });
    form.querySelectorAll('input.invalid').forEach(i => i.classList.remove('invalid'));
    const alert = form.querySelector('.form-alert');
    if (alert) alert.remove();
}

function showFormErrors(form, fieldErrors, generalMessage) {
    Object.entries(fieldErrors || {}).forEach(([name, message]) => {
        const f = form.querySelector(`[data-field="${CSS.escape(name)}"]`);
        if (f) {
            f.classList.add('invalid');
            f.querySelector('.error').textContent = message;
            return;
        }
        // Line-item errors such as "items[0].quantity"
        const inputEl = form.querySelector(`[data-err-key="${CSS.escape(name)}"]`);
        if (inputEl) {
            inputEl.classList.add('invalid');
            const slot = inputEl.parentElement.querySelector('.field-error');
            if (slot) slot.textContent = message;
        }
    });
    if (generalMessage) {
        const alert = document.createElement('div');
        alert.className = 'form-alert';
        alert.textContent = generalMessage;
        form.prepend(alert);
        alert.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
}

/** Browser validation first, then submit; server errors appear inside the form. */
function bindForm(form, validate, submit) {
    form.addEventListener('submit', async e => {
        e.preventDefault();
        clearFormErrors(form);
        const data = readForm(form);
        const errors = validate(data, form);
        if (Object.keys(errors).length > 0) {
            showFormErrors(form, errors, 'Please fix the highlighted fields.');
            return;
        }
        const button = e.submitter || form.querySelector('button[type="submit"]');
        const buttons = form.querySelectorAll('button[type="submit"]');
        const originalText = button.textContent;
        buttons.forEach(b => { b.disabled = true; });
        button.textContent = 'Saving…';
        try {
            await submit(data, button);
        } catch (err) {
            showFormErrors(form, err.fieldErrors, err.message);
        } finally {
            buttons.forEach(b => { b.disabled = false; });
            button.textContent = originalText;
        }
    });
}

function formActions(submitLabel) {
    return `<div class="form-actions">
        <button type="button" class="btn btn-ghost" data-close-modal-inline>Cancel</button>
        <button type="submit" class="btn">${esc(submitLabel)}</button>
    </div>`;
}

function wireInlineCancel(container) {
    container.querySelectorAll('[data-close-modal-inline]').forEach(b => b.addEventListener('click', closeModal));
}

/* ======================================================================
   Router
   ====================================================================== */

let viewToken = 0;

function startView(title, actionsHtml) {
    viewToken += 1;
    document.getElementById('pageTitle').textContent = title;
    document.getElementById('topbarActions').innerHTML = actionsHtml || '';
    document.title = `${title} · Billing Management System`;
    return viewToken;
}

function isStale(token) {
    return token !== viewToken;
}

const routes = [
    { pattern: /^#\/dashboard$/, nav: 'dashboard', render: () => renderDashboard() },
    { pattern: /^#\/customers$/, nav: 'customers', render: () => renderCustomers() },
    { pattern: /^#\/customers\/([^/?]+)$/, nav: 'customers', render: m => renderCustomerDetail(m[1]) },
    { pattern: /^#\/invoices$/, nav: 'invoices', render: () => renderInvoices() },
    { pattern: /^#\/invoices\/new(?:\?customer=([^&]+))?$/, nav: 'invoices', render: m => renderInvoiceEditor(null, m[1] || null) },
    { pattern: /^#\/invoices\/([^/?]+)\/edit$/, nav: 'invoices', render: m => renderInvoiceEditor(m[1], null) },
    { pattern: /^#\/invoices\/([^/?]+)$/, nav: 'invoices', render: m => renderInvoiceDetail(m[1]) },
    { pattern: /^#\/payments$/, nav: 'payments', render: () => renderPayments() }
];

function router() {
    const hash = location.hash || '#/dashboard';
    for (const route of routes) {
        const match = hash.match(route.pattern);
        if (match) {
            document.querySelectorAll('.nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === route.nav));
            document.getElementById('sidebar').classList.remove('open');
            closeModal();
            route.render(match);
            window.scrollTo(0, 0);
            return;
        }
    }
    location.hash = '#/dashboard';
}

function refresh() {
    router();
}

/* ======================================================================
   Shared tables
   ====================================================================== */

function invoiceTable(invoices, showCustomer) {
    return `<div class="table-wrap"><table>
        <thead><tr>
            <th>Invoice</th>${showCustomer ? '<th>Customer</th>' : ''}
            <th class="hide-sm">Issued</th><th>Due</th>
            <th class="num">Total</th><th class="num">Balance</th><th>Status</th>
        </tr></thead>
        <tbody>${invoices.map(i => `
            <tr>
                <td><a href="#/invoices/${esc(i.id)}">${esc(invoiceNo(i))}</a></td>
                ${showCustomer ? `<td>${i.customerName ? `<a href="#/customers/${esc(i.customerId)}">${esc(i.customerName)}</a>` : '—'}</td>` : ''}
                <td class="hide-sm">${esc(fmtDate(i.issueDate))}</td>
                <td>${esc(fmtDate(i.dueDate))}</td>
                <td class="num money">${esc(fmtMoney(i.totalAmount))}</td>
                <td class="num money ${i.overdue ? 'due' : ''}">${esc(fmtMoney(i.balanceDue))}</td>
                <td>${badge(i.status)} ${i.overdue ? overdueBadge(i.daysOverdue) : ''}</td>
            </tr>`).join('')}</tbody></table></div>`;
}

function paymentTable(payments, opts) {
    const o = opts || {};
    return `<div class="table-wrap"><table>
        <thead><tr>
            <th>Date</th>${o.hideInvoice ? '' : '<th>Invoice</th>'}${o.hideCustomer ? '' : '<th class="hide-sm">Customer</th>'}
            <th>Method</th><th class="hide-sm">Reference</th><th class="num">Amount</th>${o.actions ? '<th></th>' : ''}
        </tr></thead>
        <tbody>${payments.map(p => `
            <tr>
                <td>${esc(fmtDate(p.paymentDate))}</td>
                ${o.hideInvoice ? '' : `<td><a href="#/invoices/${esc(p.invoiceId)}">${esc(p.invoiceNumber || '—')}</a></td>`}
                ${o.hideCustomer ? '' : `<td class="hide-sm">${p.customerName ? `<a href="#/customers/${esc(p.customerId)}">${esc(p.customerName)}</a>` : '—'}</td>`}
                <td>${esc(label(p.method))}</td>
                <td class="hide-sm">${esc(p.reference || '—')}</td>
                <td class="num money">${esc(fmtMoney(p.amount))}</td>
                ${o.actions ? `<td class="actions">
                    <button class="btn-link" data-action="edit-payment" data-id="${esc(p.id)}">Edit</button>
                    <button class="btn-link danger" data-action="delete-payment" data-id="${esc(p.id)}"
                            data-amount="${esc(p.amount)}">Delete</button>
                </td>` : ''}
            </tr>`).join('')}</tbody></table></div>`;
}

/* ======================================================================
   Dashboard
   ====================================================================== */

async function renderDashboard() {
    const token = startView('Dashboard', `
        <a class="btn" href="#/invoices/new">+ New invoice</a>
        <button class="btn btn-ghost" data-action="new-customer">+ New customer</button>`);
    app.innerHTML = loadingHTML('Loading dashboard…');
    try {
        const s = await api('GET', '/api/dashboard/summary');
        if (isStale(token)) return;

        const stat = (text, value, href, extra) => `<a class="stat ${extra || ''}" href="${href}">
            <div class="stat-label">${esc(text)}</div><div class="stat-value">${value}</div></a>`;
        const moneyStat = (text, value, href, warn) =>
            stat(text, `<span class="money">${esc(fmtMoney(value))}</span>`, href, warn && Number(value) > 0 ? 'warn' : '');

        app.innerHTML = `
            <div class="stats money-stats">
                ${moneyStat('Total billed', s.totalBilled, '#/invoices')}
                ${moneyStat('Collected', s.totalCollected, '#/payments')}
                ${moneyStat('Outstanding', s.outstandingAmount, '#/invoices')}
                ${moneyStat('Overdue amount', s.overdueAmount, '#/invoices', true)}
                ${moneyStat('Collected this month', s.collectedThisMonth, '#/payments')}
            </div>
            <div class="stats">
                ${stat('Customers', esc(s.totalCustomers), '#/customers')}
                ${stat('Active customers', esc(s.activeCustomers), '#/customers')}
                ${stat('Invoices', esc(s.totalInvoices), '#/invoices')}
                ${stat('Drafts', esc(s.draftInvoices), '#/invoices')}
                ${stat('Unpaid', esc(s.issuedInvoices), '#/invoices')}
                ${stat('Partially paid', esc(s.partiallyPaidInvoices), '#/invoices')}
                ${stat('Paid', esc(s.paidInvoices), '#/invoices')}
                ${stat('Overdue', esc(s.overdueInvoices), '#/invoices', s.overdueInvoices > 0 ? 'warn' : '')}
            </div>
            <div class="grid-2">
                <section class="panel">
                    <div class="panel-head"><h2>Recent invoices</h2><a href="#/invoices">View all</a></div>
                    <div class="panel-body flush">${s.recentInvoices.length
                        ? invoiceTable(s.recentInvoices, true)
                        : emptyHTML('No invoices yet', 'Create your first invoice to start billing.',
                            '<a class="btn" href="#/invoices/new">+ New invoice</a>')}</div>
                </section>
                <section class="panel">
                    <div class="panel-head"><h2>Overdue invoices</h2><a href="#/invoices">View all</a></div>
                    <div class="panel-body flush">${s.overdueInvoiceList.length
                        ? invoiceTable(s.overdueInvoiceList, true)
                        : emptyHTML('Nothing overdue', 'All open invoices are within their due dates.')}</div>
                </section>
            </div>
            <section class="panel">
                <div class="panel-head"><h2>Recent payments</h2><a href="#/payments">View all</a></div>
                <div class="panel-body flush">${s.recentPayments.length
                    ? paymentTable(s.recentPayments)
                    : emptyHTML('No payments yet', 'Payments recorded against issued invoices appear here.')}</div>
            </section>
            <section class="panel">
                <div class="panel-head"><h2>Quick navigation</h2></div>
                <div class="panel-body quick-links">
                    <a class="btn" href="#/invoices/new">+ New invoice</a>
                    <button class="btn btn-ghost" data-action="new-customer">+ New customer</button>
                    <a class="btn btn-ghost" href="#/customers">Customers</a>
                    <a class="btn btn-ghost" href="#/invoices">Invoices</a>
                    <a class="btn btn-ghost" href="#/payments">Payments</a>
                </div>
            </section>`;
    } catch (err) {
        if (isStale(token)) return;
        app.innerHTML = errorHTML(err.message);
    }
}

/* ======================================================================
   Customers
   ====================================================================== */

async function renderCustomers() {
    const token = startView('Customers', '<button class="btn" data-action="new-customer">+ New customer</button>');
    app.innerHTML = `
        <section class="panel">
            <div class="toolbar">
                <input class="grow" id="customerSearch" type="search" placeholder="Search name, email, phone or PAN…"
                       value="${esc(customerFilters.search)}" aria-label="Search customers">
                <select id="customerStatusFilter" aria-label="Filter by status">
                    ${options(CUSTOMER_STATUSES, customerFilters.status, 'All statuses')}
                </select>
                <select id="customerTypeFilter" aria-label="Filter by type">
                    ${options(CUSTOMER_TYPES, customerFilters.customerType, 'All types')}
                </select>
                <button class="btn btn-ghost" id="customerClear">Clear</button>
            </div>
            <div id="customerList">${loadingHTML('Loading customers…')}</div>
        </section>`;

    let debounce;
    document.getElementById('customerSearch').addEventListener('input', e => {
        customerFilters.search = e.target.value;
        clearTimeout(debounce);
        debounce = setTimeout(() => loadCustomerList(token), 300);
    });
    document.getElementById('customerStatusFilter').addEventListener('change', e => {
        customerFilters.status = e.target.value;
        loadCustomerList(token);
    });
    document.getElementById('customerTypeFilter').addEventListener('change', e => {
        customerFilters.customerType = e.target.value;
        loadCustomerList(token);
    });
    document.getElementById('customerClear').addEventListener('click', () => {
        customerFilters.search = '';
        customerFilters.status = '';
        customerFilters.customerType = '';
        renderCustomers();
    });
    await loadCustomerList(token);
}

async function loadCustomerList(token) {
    const list = document.getElementById('customerList');
    if (!list) return;
    list.innerHTML = loadingHTML('Loading customers…');
    try {
        const customers = await api('GET', '/api/customers' + query(customerFilters));
        if (isStale(token)) return;
        const filtered = customerFilters.search || customerFilters.status || customerFilters.customerType;
        if (customers.length === 0) {
            list.innerHTML = filtered
                ? emptyHTML('No matching customers', 'Try a different search or clear the filters.')
                : emptyHTML('No customers yet', 'Customers you add will appear here.',
                    '<button class="btn" data-action="new-customer">+ New customer</button>');
            return;
        }
        list.innerHTML = `<div class="table-wrap"><table>
            <thead><tr><th>Name</th><th>Email</th><th class="hide-sm">Phone</th><th class="hide-sm">Type</th><th>Status</th><th></th></tr></thead>
            <tbody>${customers.map(c => `
                <tr>
                    <td><a href="#/customers/${esc(c.id)}">${esc(c.name)}</a>
                        ${c.panNumber ? `<span class="sub">PAN ${esc(c.panNumber)}</span>` : ''}</td>
                    <td>${esc(c.email)}</td>
                    <td class="hide-sm">${esc(c.phone)}</td>
                    <td class="hide-sm">${esc(label(c.customerType))}</td>
                    <td>${badge(c.status)}</td>
                    <td class="actions">
                        <a class="btn-link" href="#/customers/${esc(c.id)}">View</a>
                        <button class="btn-link" data-action="edit-customer" data-id="${esc(c.id)}">Edit</button>
                        <button class="btn-link danger" data-action="delete-customer" data-id="${esc(c.id)}"
                                data-name="${esc(c.name)}">Delete</button>
                    </td>
                </tr>`).join('')}</tbody></table></div>
            <div class="panel-body" style="color:var(--muted);font-size:13px">${customers.length} customer(s)</div>`;
    } catch (err) {
        if (isStale(token)) return;
        list.innerHTML = `<div class="panel-body">${errorHTML(err.message)}</div>`;
    }
}

async function renderCustomerDetail(id) {
    const token = startView('Customer');
    app.innerHTML = loadingHTML('Loading customer…');
    try {
        const [summary, invoices] = await Promise.all([
            api('GET', `/api/customers/${encodeURIComponent(id)}/summary`),
            api('GET', `/api/customers/${encodeURIComponent(id)}/invoices`)
        ]);
        if (isStale(token)) return;
        const c = summary.customer;
        const active = c.status === 'ACTIVE';

        document.getElementById('pageTitle').textContent = c.name;
        document.getElementById('topbarActions').innerHTML = `
            ${active ? `<a class="btn" href="#/invoices/new?customer=${esc(c.id)}">+ New invoice</a>` : ''}
            <button class="btn btn-ghost" data-action="edit-customer" data-id="${esc(c.id)}">Edit</button>
            <button class="btn btn-danger" data-action="delete-customer" data-id="${esc(c.id)}" data-name="${esc(c.name)}">Delete</button>`;

        const mini = (text, value, cls) =>
            `<div class="mini-stat"><div class="stat-label">${esc(text)}</div><div class="stat-value ${cls || ''}">${value}</div></div>`;

        app.innerHTML = `
            <a class="back-link" href="#/customers">← Back to customers</a>
            <section class="panel">
                <div class="panel-body">
                    <div class="detail-head"><div>
                        <h2>${esc(c.name)}</h2>
                        <div class="meta">${badge(c.status)} <span>${esc(label(c.customerType))}</span>
                            ${c.panNumber ? `<span>· PAN ${esc(c.panNumber)}</span>` : ''}</div>
                    </div></div>
                </div>
            </section>
            <section class="panel">
                <div class="panel-head"><h2>Account summary</h2></div>
                <div class="panel-body">
                    <div class="mini-stats">
                        ${mini('Invoices', esc(summary.totalInvoices))}
                        ${mini('Total billed', esc(fmtMoney(summary.totalBilled)), 'money')}
                        ${mini('Paid', esc(fmtMoney(summary.totalPaid)), 'money')}
                        ${mini('Outstanding', esc(fmtMoney(summary.outstandingBalance)), 'money')}
                        ${mini(`Overdue (${summary.overdueInvoices})`, esc(fmtMoney(summary.overdueAmount)), 'money')}
                    </div>
                </div>
            </section>
            <div class="grid-2">
                <section class="panel">
                    <div class="panel-head"><h2>Details</h2></div>
                    <div class="panel-body">
                        <dl class="kv">
                            <dt>Email</dt><dd><a href="mailto:${esc(c.email)}">${esc(c.email)}</a></dd>
                            <dt>Phone</dt><dd>${esc(c.phone)}</dd>
                            <dt>Address</dt><dd>${esc(c.address || '—')}</dd>
                            <dt>PAN</dt><dd>${esc(c.panNumber || '—')}</dd>
                            <dt>Notes</dt><dd class="notes">${esc(c.notes || '—')}</dd>
                            <dt>Created</dt><dd>${esc(fmtDateTime(c.createdAt))}</dd>
                            <dt>Last updated</dt><dd>${esc(fmtDateTime(c.updatedAt))}</dd>
                        </dl>
                    </div>
                </section>
                <section class="panel">
                    <div class="panel-head"><h2>Invoice counts</h2></div>
                    <div class="panel-body">
                        <dl class="kv">
                            <dt>Drafts</dt><dd>${esc(summary.draftInvoices)}</dd>
                            <dt>Unpaid / partly paid</dt><dd>${esc(summary.unpaidInvoices)}</dd>
                            <dt>Paid</dt><dd>${esc(summary.paidInvoices)}</dd>
                            <dt>Overdue</dt><dd>${esc(summary.overdueInvoices)}</dd>
                        </dl>
                        ${active ? '' : '<p class="hint" style="color:var(--muted);margin-bottom:0">This customer is inactive, so new invoices cannot be created. Payments on existing invoices can still be recorded.</p>'}
                    </div>
                </section>
            </div>
            <section class="panel">
                <div class="panel-head"><h2>Invoices</h2>
                    ${active ? `<a class="btn btn-sm" href="#/invoices/new?customer=${esc(c.id)}">+ New invoice</a>` : ''}</div>
                <div class="panel-body flush">${invoices.length
                    ? invoiceTable(invoices, false)
                    : emptyHTML('No invoices for this customer', active ? 'Create one with the button above.' : '')}</div>
            </section>`;
    } catch (err) {
        if (isStale(token)) return;
        app.innerHTML = `<a class="back-link" href="#/customers">← Back to customers</a>${errorHTML(err.message)}`;
    }
}

function customerFormHTML(c) {
    const isEdit = Boolean(c.id);
    return `<form novalidate>
        <div class="form-grid">
            ${field('name', 'Name', input('name', c.name, 'maxlength="120" autocomplete="off"'), { required: true, full: true,
                hint: 'Person or company name, as it should appear on invoices.' })}
            ${field('email', 'Email', input('email', c.email, 'type="email" maxlength="120" autocomplete="off"'), { required: true })}
            ${field('phone', 'Phone', input('phone', c.phone, 'maxlength="20" placeholder="+977 9800000000"'), { required: true })}
            ${field('customerType', 'Customer type', select('customerType', options(CUSTOMER_TYPES, c.customerType || 'INDIVIDUAL')), { required: true })}
            ${field('status', 'Status', select('status', options(CUSTOMER_STATUSES, c.status || 'ACTIVE')),
                { hint: 'Only active customers can receive new invoices.' })}
            ${field('panNumber', 'PAN number', input('panNumber', c.panNumber, 'maxlength="9" inputmode="numeric" placeholder="9 digits"'))}
            ${field('address', 'Address', input('address', c.address, 'maxlength="255"'))}
            ${field('notes', 'Notes', textarea('notes', c.notes, 'maxlength="2000"'), { full: true })}
        </div>
        ${formActions(isEdit ? 'Save changes' : 'Create customer')}
    </form>`;
}

function validateCustomer(d) {
    const e = {};
    if (!d.name) e.name = 'Name is required.';
    if (!d.email) e.email = 'Email is required.';
    else if (!EMAIL_PATTERN.test(d.email)) e.email = 'Enter a valid email address.';
    if (!d.phone) e.phone = 'Phone is required.';
    else if (!PHONE_PATTERN.test(d.phone)) e.phone = 'Use 7-20 digits, spaces, (), - and an optional leading +.';
    if (!d.customerType) e.customerType = 'Choose a customer type.';
    if (d.panNumber && !PAN_PATTERN.test(d.panNumber)) e.panNumber = 'PAN must be exactly 9 digits.';
    return e;
}

async function openCustomerForm(customerId) {
    let customer = {};
    if (customerId) {
        openModal('Edit customer', loadingHTML('Loading customer…'));
        try {
            customer = await api('GET', `/api/customers/${encodeURIComponent(customerId)}`);
        } catch (err) {
            closeModal();
            toast(err.message, 'error');
            return;
        }
    }
    const body = openModal(customerId ? 'Edit customer' : 'New customer', customerFormHTML(customer));
    wireInlineCancel(body);
    bindForm(body.querySelector('form'), validateCustomer, async data => {
        if (customerId) {
            await api('PUT', `/api/customers/${encodeURIComponent(customerId)}`, data);
            closeModal();
            toast('Customer updated.');
            refresh();
        } else {
            const created = await api('POST', '/api/customers', data);
            closeModal();
            toast(`Customer "${created.name}" created.`);
            location.hash = `#/customers/${created.id}`;
        }
    });
}

async function deleteCustomer(id, name) {
    if (!await confirmAction(`Delete customer "${name}"? This cannot be undone.`)) return;
    try {
        await api('DELETE', `/api/customers/${encodeURIComponent(id)}`);
        toast('Customer deleted.');
        if (location.hash === '#/customers') refresh(); else location.hash = '#/customers';
    } catch (err) {
        toast(err.message, 'error');
    }
}

/* ======================================================================
   Invoices: list
   ====================================================================== */

async function renderInvoices() {
    const token = startView('Invoices', '<a class="btn" href="#/invoices/new">+ New invoice</a>');
    app.innerHTML = `
        <section class="panel">
            <div class="panel-head"><h2>Overdue invoices</h2></div>
            <div class="panel-body flush" id="overdueList">${loadingHTML('Checking for overdue invoices…')}</div>
        </section>
        <section class="panel">
            <div class="panel-head"><h2>All invoices</h2></div>
            <div class="toolbar">
                <input class="grow" id="invoiceSearch" type="search" placeholder="Search invoice number, e.g. 2026-0012"
                       value="${esc(invoiceFilters.search)}" aria-label="Search invoice number">
                <select id="invoiceStatusFilter" aria-label="Filter by status">${options(INVOICE_STATUSES, invoiceFilters.status, 'All statuses')}</select>
                <select id="invoiceCustomerFilter" aria-label="Filter by customer"><option value="">All customers</option></select>
                <button class="btn btn-ghost" id="invoiceClear">Clear</button>
            </div>
            <div id="invoiceList">${loadingHTML('Loading invoices…')}</div>
        </section>`;

    let debounce;
    document.getElementById('invoiceSearch').addEventListener('input', e => {
        invoiceFilters.search = e.target.value;
        clearTimeout(debounce);
        debounce = setTimeout(() => loadInvoiceList(token), 300);
    });
    document.getElementById('invoiceStatusFilter').addEventListener('change', e => {
        invoiceFilters.status = e.target.value;
        loadInvoiceList(token);
    });
    document.getElementById('invoiceCustomerFilter').addEventListener('change', e => {
        invoiceFilters.customerId = e.target.value;
        loadInvoiceList(token);
    });
    document.getElementById('invoiceClear').addEventListener('click', () => {
        invoiceFilters.status = '';
        invoiceFilters.customerId = '';
        invoiceFilters.search = '';
        renderInvoices();
    });

    fillCustomerSelect(document.getElementById('invoiceCustomerFilter'), invoiceFilters.customerId, token);

    api('GET', '/api/invoices/overdue').then(list => {
        if (isStale(token)) return;
        document.getElementById('overdueList').innerHTML = list.length
            ? invoiceTable(list, true)
            : emptyHTML('Nothing overdue', 'All open invoices are within their due dates.');
    }).catch(err => {
        if (isStale(token)) return;
        document.getElementById('overdueList').innerHTML = `<div class="panel-body">${errorHTML(err.message)}</div>`;
    });

    await loadInvoiceList(token);
}

function fillCustomerSelect(sel, selectedId, token) {
    api('GET', '/api/customers').then(customers => {
        if (isStale(token)) return;
        customers.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c.id;
            opt.textContent = c.name;
            if (c.id === selectedId) opt.selected = true;
            sel.appendChild(opt);
        });
    }).catch(() => { /* the list itself shows errors */ });
}

async function loadInvoiceList(token) {
    const list = document.getElementById('invoiceList');
    if (!list) return;
    list.innerHTML = loadingHTML('Loading invoices…');
    try {
        const invoices = await api('GET', '/api/invoices' + query(invoiceFilters));
        if (isStale(token)) return;
        const filtered = invoiceFilters.status || invoiceFilters.customerId || invoiceFilters.search;
        if (invoices.length === 0) {
            list.innerHTML = filtered
                ? emptyHTML('No matching invoices', 'Try different filters. Drafts have no number until they are issued.')
                : emptyHTML('No invoices yet', 'Create an invoice for one of your customers.',
                    '<a class="btn" href="#/invoices/new">+ New invoice</a>');
            return;
        }
        list.innerHTML = invoiceTable(invoices, true) +
            `<div class="panel-body" style="color:var(--muted);font-size:13px">${invoices.length} invoice(s)</div>`;
    } catch (err) {
        if (isStale(token)) return;
        list.innerHTML = `<div class="panel-body">${errorHTML(err.message)}</div>`;
    }
}

/* ======================================================================
   Invoices: editor (create and edit drafts)
   ====================================================================== */

function itemRowHTML(item, index) {
    const it = item || {};
    return `<tr data-row>
        <td>
            <input data-col="description" data-err-key="items[${index}].description" value="${esc(it.description)}"
                   maxlength="200" placeholder="Item or service description" aria-label="Description">
            <span class="field-error"></span>
        </td>
        <td class="col-qty">
            <input data-col="quantity" data-err-key="items[${index}].quantity" type="number" min="0.01" step="0.01"
                   value="${esc(it.quantity !== undefined ? it.quantity : 1)}" aria-label="Quantity">
            <span class="field-error"></span>
        </td>
        <td class="col-price">
            <input data-col="unitPrice" data-err-key="items[${index}].unitPrice" type="number" min="0" step="0.01"
                   value="${esc(it.unitPrice)}" placeholder="0.00" aria-label="Unit price">
            <span class="field-error"></span>
        </td>
        <td class="col-total money" data-line-total>—</td>
        <td class="col-remove"><button type="button" class="icon-btn" data-remove-row aria-label="Remove item">✕</button></td>
    </tr>`;
}

/** Re-numbers error keys after rows are added or removed, so server errors map to the right row. */
function renumberRows(tbody) {
    tbody.querySelectorAll('[data-row]').forEach((row, i) => {
        row.querySelectorAll('[data-col]').forEach(inp => {
            inp.dataset.errKey = `items[${i}].${inp.dataset.col}`;
        });
    });
}

function readItems(tbody) {
    return Array.from(tbody.querySelectorAll('[data-row]')).map(row => ({
        description: row.querySelector('[data-col="description"]').value.trim(),
        quantity: row.querySelector('[data-col="quantity"]').value.trim(),
        unitPrice: row.querySelector('[data-col="unitPrice"]').value.trim()
    }));
}

/** Preview only (in paisa, to avoid floating-point surprises). The server recalculates on save. */
function previewTotals(items, discount, taxRate) {
    let subtotal = 0;
    const lines = items.map(it => {
        const q = Number(it.quantity);
        const p = Number(it.unitPrice);
        const cents = Number.isFinite(q) && Number.isFinite(p) ? Math.round(q * p * 100 + 1e-9) : 0;
        subtotal += cents;
        return cents;
    });
    const discountCents = Math.round((Number(discount) || 0) * 100);
    const taxable = subtotal - discountCents;
    const tax = Math.round(taxable * (Number(taxRate) || 0) / 100 + 1e-9);
    return { lines, subtotal, discount: discountCents, taxable, tax, total: taxable + tax };
}

async function renderInvoiceEditor(invoiceId, presetCustomerId) {
    const isEdit = Boolean(invoiceId);
    const token = startView(isEdit ? 'Edit invoice' : 'New invoice');
    app.innerHTML = loadingHTML('Loading…');

    let customers;
    let invoice = null;
    try {
        const requests = [api('GET', '/api/customers')];
        if (isEdit) requests.push(api('GET', `/api/invoices/${encodeURIComponent(invoiceId)}`));
        const results = await Promise.all(requests);
        if (isStale(token)) return;
        customers = results[0];
        invoice = isEdit ? results[1] : null;
    } catch (err) {
        if (isStale(token)) return;
        app.innerHTML = errorHTML(err.message);
        return;
    }

    if (isEdit && !invoice.canEdit) {
        app.innerHTML = `<a class="back-link" href="#/invoices/${esc(invoice.id)}">← Back to invoice</a>
            ${errorHTML(`Invoice ${invoiceNo(invoice)} is ${label(invoice.status)} and can no longer be edited. Only drafts can be changed.`)}`;
        return;
    }
    const activeCustomers = customers.filter(c => c.status === 'ACTIVE');
    if (activeCustomers.length === 0) {
        app.innerHTML = `<section class="panel">${emptyHTML('No active customers',
            'An invoice must belong to an active customer. Create a customer first.',
            '<button class="btn" data-action="new-customer">+ New customer</button>')}</section>`;
        return;
    }

    const inv = invoice || {};
    const selectedCustomer = inv.customerId || presetCustomerId || '';
    const issueDate = inv.issueDate || todayISO();
    const dueDate = inv.dueDate || addDaysISO(issueDate, DEFAULT_TERMS_DAYS);
    const items = inv.items && inv.items.length ? inv.items : [{ description: '', quantity: 1, unitPrice: '' }];

    let customerOptions = '<option value="">Choose a customer…</option>';
    activeCustomers.forEach(c => {
        customerOptions += `<option value="${esc(c.id)}" ${c.id === selectedCustomer ? 'selected' : ''}>${esc(c.name)}${c.panNumber ? ` (PAN ${esc(c.panNumber)})` : ''}</option>`;
    });

    const backHref = isEdit ? `#/invoices/${esc(inv.id)}` : '#/invoices';
    app.innerHTML = `
        <a class="back-link" href="${backHref}">← Back</a>
        <form id="invoiceForm" novalidate>
            <section class="panel">
                <div class="panel-head"><h2>Invoice details</h2>${isEdit ? badge('DRAFT') : ''}</div>
                <div class="panel-body">
                    <div class="form-grid">
                        ${field('customerId', 'Customer', select('customerId', customerOptions), { required: true, full: true,
                            hint: 'Only active customers are listed.' })}
                        ${field('issueDate', 'Issue date', input('issueDate', issueDate, 'type="date"'), { required: true })}
                        ${field('dueDate', 'Due date', input('dueDate', dueDate, 'type="date"'), { required: true,
                            hint: `Default: ${DEFAULT_TERMS_DAYS} days after the issue date.` })}
                    </div>
                </div>
            </section>

            <section class="panel">
                <div class="panel-head"><h2>Items</h2><button type="button" class="btn btn-sm btn-ghost" id="addItem">+ Add item</button></div>
                <div class="table-wrap">
                    <table class="items-table">
                        <thead><tr><th>Description</th><th class="col-qty">Qty</th><th class="col-price">Unit price (Rs.)</th>
                            <th class="col-total">Amount</th><th class="col-remove"></th></tr></thead>
                        <tbody id="itemRows">${items.map((it, i) => itemRowHTML(it, i)).join('')}</tbody>
                    </table>
                </div>
                <div class="editor-foot">
                    <div>
                        ${field('notes', 'Notes (printed on the invoice)', textarea('notes', inv.notes, 'maxlength="2000" placeholder="Payment instructions, bank details, thank-you note…"'), {})}
                    </div>
                    <div>
                        <dl class="totals">
                            <dt>Subtotal</dt><dd data-t="subtotal">—</dd>
                            <dt><label for="f-discountAmount">Discount (Rs.)</label></dt>
                            <dd class="inline-input" data-field="discountAmount"><input id="f-discountAmount" name="discountAmount" type="number" min="0" step="0.01"
                                value="${esc(inv.discountAmount !== undefined && inv.discountAmount !== null ? inv.discountAmount : 0)}"><span class="error"></span></dd>
                            <dt>Taxable amount</dt><dd data-t="taxable">—</dd>
                            <dt><label for="f-taxRate">VAT (%)</label></dt>
                            <dd class="inline-input" data-field="taxRate"><input id="f-taxRate" name="taxRate" type="number" min="0" max="100" step="0.01"
                                value="${esc(inv.taxRate !== undefined && inv.taxRate !== null ? inv.taxRate : DEFAULT_TAX_RATE)}"><span class="error"></span></dd>
                            <dt>VAT amount</dt><dd data-t="tax">—</dd>
                            <dt class="grand">Total</dt><dd class="grand" data-t="total">—</dd>
                        </dl>
                        <p class="preview-note">Preview. Final amounts are calculated by the server when you save.</p>
                    </div>
                </div>
            </section>

            <div class="form-actions" style="margin-top:0">
                <a class="btn btn-ghost" href="${backHref}">Cancel</a>
                <button type="submit" class="btn btn-ghost" data-mode="draft">Save as draft</button>
                <button type="submit" class="btn" data-mode="issue">Save and issue</button>
            </div>
        </form>`;

    const form = document.getElementById('invoiceForm');
    const tbody = document.getElementById('itemRows');

    const recalc = () => {
        const t = previewTotals(readItems(tbody), form.discountAmount.value, form.taxRate.value);
        tbody.querySelectorAll('[data-line-total]').forEach((cell, i) => {
            cell.textContent = fmtMoney(t.lines[i] / 100);
        });
        form.querySelector('[data-t="subtotal"]').textContent = fmtMoney(t.subtotal / 100);
        form.querySelector('[data-t="taxable"]').textContent = fmtMoney(t.taxable / 100);
        form.querySelector('[data-t="tax"]').textContent = fmtMoney(t.tax / 100);
        form.querySelector('[data-t="total"]').textContent = fmtMoney(t.total / 100);
        return t;
    };

    document.getElementById('addItem').addEventListener('click', () => {
        tbody.insertAdjacentHTML('beforeend', itemRowHTML(null, tbody.querySelectorAll('[data-row]').length));
        tbody.lastElementChild.querySelector('input').focus();
        recalc();
    });
    tbody.addEventListener('click', e => {
        const btn = e.target.closest('[data-remove-row]');
        if (!btn) return;
        if (tbody.querySelectorAll('[data-row]').length === 1) {
            toast('An invoice needs at least one item.', 'error');
            return;
        }
        btn.closest('[data-row]').remove();
        renumberRows(tbody);
        recalc();
    });
    form.addEventListener('input', recalc);
    form.issueDate.addEventListener('change', () => {
        if (!isEdit && form.issueDate.value) form.dueDate.value = addDaysISO(form.issueDate.value, DEFAULT_TERMS_DAYS);
    });
    recalc();

    const validate = (d) => {
        const e = {};
        if (!d.customerId) e.customerId = 'Choose a customer.';
        if (!d.issueDate) e.issueDate = 'Issue date is required.';
        if (!d.dueDate) e.dueDate = 'Due date is required.';
        if (d.issueDate && d.dueDate && d.dueDate < d.issueDate) e.dueDate = 'Due date cannot be earlier than the issue date.';
        readItems(tbody).forEach((it, i) => {
            if (!it.description) e[`items[${i}].description`] = 'Description is required.';
            if (!it.quantity || Number(it.quantity) <= 0) e[`items[${i}].quantity`] = 'Must be more than 0.';
            else if (!MONEY_PATTERN.test(it.quantity)) e[`items[${i}].quantity`] = 'Max 2 decimals.';
            if (it.unitPrice === '' || Number(it.unitPrice) < 0) e[`items[${i}].unitPrice`] = 'Enter a price (0 or more).';
            else if (!MONEY_PATTERN.test(it.unitPrice)) e[`items[${i}].unitPrice`] = 'Max 2 decimals.';
        });
        const t = recalc();
        if (d.discountAmount && !MONEY_PATTERN.test(d.discountAmount)) e.discountAmount = 'Invalid amount.';
        else if (t.discount > t.subtotal) e.discountAmount = 'Discount cannot exceed the subtotal.';
        const rate = Number(d.taxRate);
        if (d.taxRate === null || Number.isNaN(rate) || rate < 0 || rate > 100) e.taxRate = '0 to 100.';
        return e;
    };

    bindForm(form, validate, async (d, button) => {
        const payload = {
            customerId: d.customerId,
            issueDate: d.issueDate,
            dueDate: d.dueDate,
            items: readItems(tbody).map(it => ({
                description: it.description,
                quantity: Number(it.quantity),
                unitPrice: Number(it.unitPrice)
            })),
            discountAmount: d.discountAmount === null ? 0 : Number(d.discountAmount),
            taxRate: Number(d.taxRate),
            notes: d.notes
        };
        const saved = isEdit
            ? await api('PUT', `/api/invoices/${encodeURIComponent(invoiceId)}`, payload)
            : await api('POST', '/api/invoices', payload);

        if (button.dataset.mode === 'issue') {
            try {
                const issued = await api('POST', `/api/invoices/${encodeURIComponent(saved.id)}/issue`);
                toast(`Invoice ${issued.invoiceNumber} issued.`);
            } catch (err) {
                toast(`Saved as draft, but could not issue: ${err.message}`, 'error');
            }
        } else {
            toast('Draft saved.');
        }
        location.hash = `#/invoices/${saved.id}`;
    });
}

/* ======================================================================
   Invoices: detail (printable)
   ====================================================================== */

async function renderInvoiceDetail(id) {
    const token = startView('Invoice');
    app.innerHTML = loadingHTML('Loading invoice…');
    try {
        const inv = await api('GET', `/api/invoices/${encodeURIComponent(id)}`);
        const [customer, payments] = await Promise.all([
            api('GET', `/api/customers/${encodeURIComponent(inv.customerId)}`).catch(() => null),
            api('GET', `/api/invoices/${encodeURIComponent(id)}/payments`)
        ]);
        if (isStale(token)) return;

        document.getElementById('pageTitle').textContent = inv.invoiceNumber ? `Invoice ${inv.invoiceNumber}` : 'Draft invoice';

        const actions = [];
        if (inv.canRecordPayment) actions.push(`<button class="btn" data-action="new-payment" data-invoice-id="${esc(inv.id)}">Record payment</button>`);
        if (inv.canIssue) actions.push(`<button class="btn" data-action="issue-invoice" data-id="${esc(inv.id)}">Issue invoice</button>`);
        if (inv.canEdit) actions.push(`<a class="btn btn-ghost" href="#/invoices/${esc(inv.id)}/edit">Edit</a>`);
        actions.push('<button class="btn btn-ghost" data-action="print">Print</button>');
        if (inv.canCancel) actions.push(`<button class="btn btn-ghost" data-action="cancel-invoice" data-id="${esc(inv.id)}">Cancel invoice</button>`);
        if (inv.canDelete) actions.push(`<button class="btn btn-danger" data-action="delete-invoice" data-id="${esc(inv.id)}">Delete draft</button>`);

        let stamp = '';
        if (inv.status === 'PAID') stamp = '<span class="stamp PAID">PAID</span>';
        else if (inv.status === 'CANCELLED') stamp = '<span class="stamp CANCELLED">CANCELLED</span>';
        else if (inv.status === 'DRAFT') stamp = '<span class="stamp DRAFT">DRAFT</span>';
        else if (inv.overdue) stamp = `<span class="stamp overdue">OVERDUE ${esc(inv.daysOverdue)} DAYS</span>`;

        const cust = customer || { name: inv.customerName || 'Unknown customer' };

        app.innerHTML = `
            <a class="back-link" href="#/invoices">← Back to invoices</a>
            <div class="invoice-actions no-print">${actions.join('')}</div>

            <article class="invoice-doc">
                <div class="doc-head">
                    <div>
                        <div class="seller-name">${esc(BUSINESS.name)}</div>
                        <div class="seller-meta">${esc(BUSINESS.address)}<br>${esc(BUSINESS.phone)} · ${esc(BUSINESS.email)}<br>PAN ${esc(BUSINESS.pan)}</div>
                    </div>
                    <div class="doc-title">
                        <h2>${inv.status === 'DRAFT' ? 'DRAFT INVOICE' : 'TAX INVOICE'}</h2>
                        <div class="number">${esc(invoiceNo(inv))}</div>
                        ${stamp}
                    </div>
                </div>
                <div class="parties">
                    <div>
                        <div class="label">Bill to</div>
                        <strong>${esc(cust.name)}</strong><br>
                        ${cust.address ? `${esc(cust.address)}<br>` : ''}
                        ${cust.phone ? `${esc(cust.phone)}<br>` : ''}
                        ${cust.email ? `${esc(cust.email)}<br>` : ''}
                        ${cust.panNumber ? `PAN ${esc(cust.panNumber)}` : ''}
                    </div>
                    <dl class="dates">
                        <dt>Issue date</dt><dd>${esc(fmtDate(inv.issueDate))}</dd>
                        <dt>Due date</dt><dd>${esc(fmtDate(inv.dueDate))}</dd>
                        <dt>Status</dt><dd>${badge(inv.status)}</dd>
                    </dl>
                </div>
                <div class="table-wrap"><table>
                    <thead><tr><th>#</th><th>Description</th><th class="num">Qty</th><th class="num">Unit price</th><th class="num">Amount</th></tr></thead>
                    <tbody>${inv.items.map((it, i) => `
                        <tr><td>${i + 1}</td><td>${esc(it.description)}</td>
                            <td class="num">${esc(Number(it.quantity).toLocaleString('en-IN', { maximumFractionDigits: 2 }))}</td>
                            <td class="num money">${esc(fmtMoney(it.unitPrice))}</td>
                            <td class="num money">${esc(fmtMoney(it.lineTotal))}</td></tr>`).join('')}</tbody>
                </table></div>
                <div class="doc-totals">
                    <dl class="totals">
                        <dt>Subtotal</dt><dd>${esc(fmtMoney(inv.subtotal))}</dd>
                        <dt>Discount</dt><dd>− ${esc(fmtMoney(inv.discountAmount))}</dd>
                        <dt>Taxable amount</dt><dd>${esc(fmtMoney(inv.taxableAmount))}</dd>
                        <dt>VAT (${esc(Number(inv.taxRate))}%)</dt><dd>${esc(fmtMoney(inv.taxAmount))}</dd>
                        <dt class="grand">Total</dt><dd class="grand">${esc(fmtMoney(inv.totalAmount))}</dd>
                        ${inv.status !== 'DRAFT' && inv.status !== 'CANCELLED' ? `
                        <dt>Amount paid</dt><dd>${esc(fmtMoney(inv.amountPaid))}</dd>
                        <dt class="grand">Balance due</dt><dd class="grand ${Number(inv.balanceDue) > 0 ? 'money due' : ''}">${esc(fmtMoney(inv.balanceDue))}</dd>` : ''}
                    </dl>
                </div>
                ${inv.notes ? `<div class="doc-notes"><div class="label">Notes</div>${esc(inv.notes)}</div>` : ''}
            </article>

            <section class="panel no-print">
                <div class="panel-head"><h2>Payments</h2>
                    ${inv.canRecordPayment ? `<button class="btn btn-sm" data-action="new-payment" data-invoice-id="${esc(inv.id)}">+ Record payment</button>` : ''}</div>
                <div class="panel-body flush">${payments.length
                    ? paymentTable(payments, { hideInvoice: true, hideCustomer: true, actions: true })
                    : emptyHTML('No payments recorded',
                        inv.status === 'DRAFT' ? 'Issue the invoice before recording payments.'
                            : inv.status === 'CANCELLED' ? 'Cancelled invoices do not accept payments.' : '')}</div>
            </section>
            <section class="panel no-print">
                <div class="panel-head"><h2>History</h2></div>
                <div class="panel-body"><dl class="kv">
                    <dt>Created</dt><dd>${esc(fmtDateTime(inv.createdAt))}</dd>
                    <dt>Issued</dt><dd>${esc(fmtDateTime(inv.issuedAt))}</dd>
                    ${inv.cancelledAt ? `<dt>Cancelled</dt><dd>${esc(fmtDateTime(inv.cancelledAt))}</dd>` : ''}
                    <dt>Last updated</dt><dd>${esc(fmtDateTime(inv.updatedAt))}</dd>
                </dl></div>
            </section>`;
    } catch (err) {
        if (isStale(token)) return;
        app.innerHTML = `<a class="back-link" href="#/invoices">← Back to invoices</a>${errorHTML(err.message)}`;
    }
}

async function issueInvoice(id) {
    if (!await confirmAction('Issue this invoice? It will receive an invoice number and can no longer be edited.',
        'Issue invoice', false)) return;
    try {
        const issued = await api('POST', `/api/invoices/${encodeURIComponent(id)}/issue`);
        toast(`Invoice ${issued.invoiceNumber} issued.`);
        refresh();
    } catch (err) {
        toast(err.message, 'error');
    }
}

async function cancelInvoice(id) {
    if (!await confirmAction('Cancel this invoice? Cancelled invoices are kept for the record but cannot be reopened or paid.',
        'Cancel invoice')) return;
    try {
        await api('POST', `/api/invoices/${encodeURIComponent(id)}/cancel`);
        toast('Invoice cancelled.');
        refresh();
    } catch (err) {
        toast(err.message, 'error');
    }
}

async function deleteInvoice(id) {
    if (!await confirmAction('Delete this draft invoice? This cannot be undone.')) return;
    try {
        await api('DELETE', `/api/invoices/${encodeURIComponent(id)}`);
        toast('Draft deleted.');
        location.hash = '#/invoices';
    } catch (err) {
        toast(err.message, 'error');
    }
}

/* ======================================================================
   Payments
   ====================================================================== */

async function renderPayments() {
    const token = startView('Payments');
    app.innerHTML = `
        <section class="panel">
            <div class="toolbar">
                <select id="paymentMethodFilter" aria-label="Filter by method">${options(PAYMENT_METHODS, paymentFilters.method, 'All methods')}</select>
                <select id="paymentCustomerFilter" aria-label="Filter by customer"><option value="">All customers</option></select>
                <label for="paymentFrom" style="font-size:13.5px;color:var(--muted)">From</label>
                <input id="paymentFrom" type="date" value="${esc(paymentFilters.from)}" style="min-width:150px;width:auto">
                <label for="paymentTo" style="font-size:13.5px;color:var(--muted)">To</label>
                <input id="paymentTo" type="date" value="${esc(paymentFilters.to)}" style="min-width:150px;width:auto">
                <button class="btn btn-ghost" id="paymentClear">Clear</button>
            </div>
            <div id="paymentList">${loadingHTML('Loading payments…')}</div>
        </section>
        <p class="hint" style="color:var(--muted);font-size:13.5px">To record a new payment, open an issued invoice and click <strong>Record payment</strong>.</p>`;

    const bind = (id, key) => document.getElementById(id).addEventListener('change', e => {
        paymentFilters[key] = e.target.value;
        loadPaymentList(token);
    });
    bind('paymentMethodFilter', 'method');
    bind('paymentCustomerFilter', 'customerId');
    bind('paymentFrom', 'from');
    bind('paymentTo', 'to');
    document.getElementById('paymentClear').addEventListener('click', () => {
        paymentFilters.method = '';
        paymentFilters.customerId = '';
        paymentFilters.from = '';
        paymentFilters.to = '';
        renderPayments();
    });
    fillCustomerSelect(document.getElementById('paymentCustomerFilter'), paymentFilters.customerId, token);
    await loadPaymentList(token);
}

async function loadPaymentList(token) {
    const list = document.getElementById('paymentList');
    if (!list) return;
    list.innerHTML = loadingHTML('Loading payments…');
    try {
        const payments = await api('GET', '/api/payments' + query(paymentFilters));
        if (isStale(token)) return;
        const filtered = paymentFilters.method || paymentFilters.customerId || paymentFilters.from || paymentFilters.to;
        if (payments.length === 0) {
            list.innerHTML = filtered
                ? emptyHTML('No matching payments', 'Try different filters.')
                : emptyHTML('No payments yet', 'Payments are recorded from an issued invoice.',
                    '<a class="btn" href="#/invoices">Go to invoices</a>');
            return;
        }
        const totalCents = payments.reduce((sum, p) => sum + Math.round(Number(p.amount) * 100), 0);
        list.innerHTML = paymentTable(payments, { actions: true }) +
            `<div class="panel-body" style="display:flex;justify-content:space-between;color:var(--muted);font-size:13.5px">
                <span>${payments.length} payment(s)</span><strong class="money" style="color:var(--text)">Total ${esc(fmtMoney(totalCents / 100))}</strong></div>`;
    } catch (err) {
        if (isStale(token)) return;
        list.innerHTML = `<div class="panel-body">${errorHTML(err.message)}</div>`;
    }
}

function paymentFormHTML(p, invoice, maxAmount) {
    const isEdit = Boolean(p.id);
    return `<form novalidate>
        <p style="margin-top:0">Invoice <strong>${esc(invoiceNo(invoice))}</strong> · ${esc(invoice.customerName || '')}<br>
            <span style="color:var(--muted)">Total ${esc(fmtMoney(invoice.totalAmount))} · Balance due ${esc(fmtMoney(invoice.balanceDue))}</span></p>
        <div class="form-grid">
            ${field('amount', 'Amount (Rs.)', input('amount', p.amount !== undefined ? p.amount : invoice.balanceDue,
                `type="number" min="0.01" step="0.01" max="${esc(maxAmount)}"`), { required: true, hint: `Maximum ${fmtMoney(maxAmount)}` })}
            ${field('paymentDate', 'Payment date', input('paymentDate', p.paymentDate || todayISO(),
                `type="date" min="${esc(invoice.issueDate)}" max="${todayISO()}"`), { required: true })}
            ${field('method', 'Method', select('method', options(PAYMENT_METHODS, p.method || 'CASH')), { required: true })}
            ${field('reference', 'Reference', input('reference', p.reference, 'maxlength="100" placeholder="Transaction ID, cheque no., …"'),
                { hint: 'Required for every method except cash.' })}
            ${field('notes', 'Notes', textarea('notes', p.notes, 'maxlength="1000"'), { full: true })}
        </div>
        ${formActions(isEdit ? 'Save changes' : 'Record payment')}
    </form>`;
}

function paymentValidator(invoice, maxAmount) {
    return d => {
        const e = {};
        if (!d.amount) e.amount = 'Amount is required.';
        else if (!MONEY_PATTERN.test(d.amount) || Number(d.amount) <= 0) e.amount = 'Enter an amount above 0 with at most 2 decimals.';
        else if (Math.round(Number(d.amount) * 100) > Math.round(Number(maxAmount) * 100)) e.amount = `Cannot be more than ${fmtMoney(maxAmount)}.`;
        if (!d.paymentDate) e.paymentDate = 'Payment date is required.';
        else if (d.paymentDate > todayISO()) e.paymentDate = 'Cannot be in the future.';
        else if (invoice.issueDate && d.paymentDate < invoice.issueDate) e.paymentDate = 'Cannot be before the invoice issue date.';
        if (!d.method) e.method = 'Choose a method.';
        if (d.method && d.method !== 'CASH' && !d.reference) e.reference = 'A reference is required for non-cash payments.';
        return e;
    };
}

function paymentPayload(d) {
    return { amount: Number(d.amount), paymentDate: d.paymentDate, method: d.method, reference: d.reference, notes: d.notes };
}

async function openPaymentForm(invoiceId) {
    openModal('Record payment', loadingHTML('Loading invoice…'));
    let invoice;
    try {
        invoice = await api('GET', `/api/invoices/${encodeURIComponent(invoiceId)}`);
    } catch (err) {
        closeModal();
        toast(err.message, 'error');
        return;
    }
    const body = openModal('Record payment', paymentFormHTML({}, invoice, invoice.balanceDue));
    wireInlineCancel(body);
    bindForm(body.querySelector('form'), paymentValidator(invoice, invoice.balanceDue), async d => {
        await api('POST', `/api/invoices/${encodeURIComponent(invoiceId)}/payments`, paymentPayload(d));
        closeModal();
        toast(`Payment of ${fmtMoney(d.amount)} recorded.`);
        refresh();
    });
}

async function editPayment(paymentId) {
    openModal('Edit payment', loadingHTML('Loading payment…'));
    let payment;
    let invoice;
    try {
        payment = await api('GET', `/api/payments/${encodeURIComponent(paymentId)}`);
        invoice = await api('GET', `/api/invoices/${encodeURIComponent(payment.invoiceId)}`);
    } catch (err) {
        closeModal();
        toast(err.message, 'error');
        return;
    }
    const max = (Math.round(Number(invoice.balanceDue) * 100) + Math.round(Number(payment.amount) * 100)) / 100;
    const body = openModal('Edit payment', paymentFormHTML(payment, invoice, max));
    wireInlineCancel(body);
    bindForm(body.querySelector('form'), paymentValidator(invoice, max), async d => {
        await api('PUT', `/api/payments/${encodeURIComponent(paymentId)}`, paymentPayload(d));
        closeModal();
        toast('Payment updated.');
        refresh();
    });
}

async function deletePayment(id, amount) {
    if (!await confirmAction(`Delete this payment of ${fmtMoney(amount)}? The invoice balance will go back up.`)) return;
    try {
        await api('DELETE', `/api/payments/${encodeURIComponent(id)}`);
        toast('Payment deleted.');
        refresh();
    } catch (err) {
        toast(err.message, 'error');
    }
}

/* ======================================================================
   Button actions (one listener for the whole page)
   ====================================================================== */

document.addEventListener('click', e => {
    const el = e.target.closest('[data-action]');
    if (!el) return;
    const { action, id, name, invoiceId, amount } = el.dataset;
    switch (action) {
        case 'new-customer': openCustomerForm(null); break;
        case 'edit-customer': openCustomerForm(id); break;
        case 'delete-customer': deleteCustomer(id, name); break;
        case 'issue-invoice': issueInvoice(id); break;
        case 'cancel-invoice': cancelInvoice(id); break;
        case 'delete-invoice': deleteInvoice(id); break;
        case 'new-payment': openPaymentForm(invoiceId); break;
        case 'edit-payment': editPayment(id); break;
        case 'delete-payment': deletePayment(id, amount); break;
        case 'print': window.print(); break;
        default: break;
    }
});

document.getElementById('menuBtn').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('open');
});

/* ======================================================================
   Start
   ====================================================================== */

window.addEventListener('hashchange', router);
checkHealth();
setInterval(checkHealth, 20000);
router();
