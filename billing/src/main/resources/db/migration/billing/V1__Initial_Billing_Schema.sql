SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS invoice_audit;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS invoice_items;
DROP TABLE IF EXISTS invoices;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE invoices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_no VARCHAR(50) NOT NULL UNIQUE,

    -- Link to Sales Service
    sales_order_number VARCHAR(50) NOT NULL UNIQUE,
    event_id BIGINT NOT NULL,

    -- Shop snapshot (from Sales)
    shop_id BIGINT NOT NULL,

    -- Seller snapshot
    seller_id BIGINT NOT NULL,
    seller_name VARCHAR(100) NOT NULL,

    -- Cashier / Billing user
    billed_by BIGINT NOT NULL,

    -- Customer snapshot
    customer_name VARCHAR(150),
    customer_mobile VARCHAR(20),
    customer_gstin VARCHAR(20),

    -- Financials (Billing owns these)
    subtotal_amount DECIMAL(12, 2) NOT NULL,
    discount_amount DECIMAL(12, 2) DEFAULT 0,
    tax_amount DECIMAL(12, 2) DEFAULT 0,
    net_amount DECIMAL(12, 2) NOT NULL,

    status VARCHAR(20) NOT NULL,
    billing_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_invoice_no (invoice_no),
    INDEX idx_invoice_sales_order (sales_order_number),
    INDEX idx_invoice_date (billing_date),
    INDEX idx_invoice_seller (seller_id),
    INDEX idx_invoice_shop (shop_id)
);


CREATE TABLE invoice_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    hsn_code VARCHAR(20),
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    discount DECIMAL(10, 2) DEFAULT 0,
    tax_rate DECIMAL(5, 2) DEFAULT 0,
    tax_amount DECIMAL(10, 2) DEFAULT 0,
    total_price DECIMAL(12, 2) NOT NULL,
    returned_quantity INT DEFAULT 0,
    CONSTRAINT fk_invoice_items_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE,
    INDEX idx_invoice_items_invoice (invoice_id),
    INDEX idx_invoice_items_product (product_id)
);

CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    payment_mode ENUM('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'BOTH') NOT NULL,
    payment_reference VARCHAR(100),
    amount DECIMAL(12, 2) NOT NULL,
    payment_status ENUM('SUCCESS', 'FAILED', 'PENDING', 'REFUNDED') DEFAULT 'SUCCESS',
    paid_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    received_by BIGINT NOT NULL,
    CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE,
    INDEX idx_payment_invoice (invoice_id),
    INDEX idx_payment_date (paid_at)
);

CREATE TABLE invoice_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    action ENUM('CREATED', 'ISSUED', 'PAID', 'PARTIALLY_PAID', 'CANCELLED', 'REFUNDED', 'RETURNED') NOT NULL,
    action_by BIGINT NOT NULL,
    remarks VARCHAR(255),
    action_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_invoice_audit_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE,
    INDEX idx_invoice_audit_invoice (invoice_id),
    INDEX idx_invoice_audit_date (action_at)
);