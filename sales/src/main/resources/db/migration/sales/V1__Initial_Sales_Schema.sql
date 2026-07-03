SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS shop_staff_assignment;
DROP TABLE IF EXISTS shop;
DROP TABLE IF EXISTS event;
DROP TABLE IF EXISTS sales_return;
DROP TABLE IF EXISTS sales_payment;
DROP TABLE IF EXISTS sales_order_item;
DROP TABLE IF EXISTS sales_order;
DROP TABLE IF EXISTS shop_shift_denomination;
DROP TABLE IF EXISTS shop_shift_session;



CREATE TABLE sales_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number VARCHAR(50) NOT NULL UNIQUE,

     -- Shop reference
    shift_session_id BIGINT DEFAULT NULL,
    shop_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,

    -- Seller snapshot
    seller_id BIGINT NOT NULL,
    seller_name VARCHAR(100) NOT NULL,

    -- Customer snapshot
    customer_name VARCHAR(150),
    customer_mobile VARCHAR(20),

    -- Financials
    order_subtotal DECIMAL(12, 2) NOT NULL,
    discount_amount DECIMAL(12, 2) DEFAULT 0,

    -- Link to Billing Service
    billing_invoice_number VARCHAR(50),
    status ENUM('CREATED', 'CONFIRMED', 'CANCELLED', 'RETURNED', 'PARTIALLY_RETURNED') DEFAULT 'CREATED',
    cancellation_reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_order_shift FOREIGN KEY (shift_session_id) REFERENCES shop_shift_session(id) ON DELETE SET NULL,

    -- Indexes
    INDEX idx_sales_order_shop (shop_id),
    INDEX idx_sales_order_seller (seller_id),
    INDEX idx_sales_order_created (created_at),
    INDEX idx_sales_order_invoice (billing_invoice_number)
);

CREATE TABLE sales_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    hsn_code VARCHAR(20),
    quantity INT NOT NULL,
    mrp DECIMAL(10, 2) NOT NULL,
    selling_price DECIMAL(10, 2) NOT NULL,
    discount DECIMAL(10, 2) DEFAULT 0,
    line_total DECIMAL(12, 2) NOT NULL,
    returned_quantity INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_item_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id) ON DELETE CASCADE,
    INDEX idx_sales_item_order (sales_order_id),
    INDEX idx_sales_item_product (product_id)
);

CREATE TABLE sales_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    payment_mode ENUM('CASH', 'ONLINE', 'UPI', 'CARD', 'BANK_TRANSFER', 'BOTH') NOT NULL,
    payment_reference VARCHAR(100),
    amount DECIMAL(12, 2) NOT NULL,
    cash_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    online_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    payment_status ENUM('SUCCESS', 'FAILED', 'REFUNDED') DEFAULT 'SUCCESS',
    paid_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_payment_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id) ON DELETE CASCADE,
    INDEX idx_sales_payment_order (sales_order_id),
    INDEX idx_sales_payment_date (paid_at)
);

CREATE TABLE sales_return (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    sales_order_id BIGINT NOT NULL,
    sales_order_item_id BIGINT NOT NULL,

    product_id BIGINT NOT NULL,

    processed_by BIGINT NOT NULL,
    processed_by_name VARCHAR(100) NOT NULL,

    quantity INT NOT NULL,
    refund_amount DECIMAL(12,2) NOT NULL,

    reason VARCHAR(255),

    billing_invoice_number VARCHAR(50),

    returned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_sales_return_order
        FOREIGN KEY (sales_order_id)
        REFERENCES sales_order(id)
);

CREATE TABLE event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_name VARCHAR(255) NOT NULL,
    event_type VARCHAR(100),
    description TEXT,
    location VARCHAR(255),
    start_date DATETIME,
    end_date DATETIME,
    is_active BOOLEAN DEFAULT TRUE,
    created_by BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);


CREATE TABLE shop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_name VARCHAR(255) NOT NULL,
    category_id BIGINT NOT NULL,
    category_name VARCHAR(100) NOT NULL DEFAULT '',
    counter_number INT NOT NULL,
    event_id BIGINT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP NULL,
    CONSTRAINT fk_shop_event FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE CASCADE,
    INDEX idx_shop_category_name (category_name)
);

CREATE TABLE shop_staff_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP NULL,
    is_active BOOLEAN DEFAULT TRUE,
    CONSTRAINT uk_shop_role UNIQUE (shop_id, role_code),
    CONSTRAINT fk_shop FOREIGN KEY (shop_id) REFERENCES shop(id),
    INDEX idx_ssa_user_event (user_id, event_id)
);



CREATE TABLE shop_shift_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    status ENUM('OPEN', 'CLOSED', 'RECONCILED') NOT NULL DEFAULT 'OPEN',
    opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP NULL DEFAULT NULL,
    opening_cash_balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    expected_closing_cash DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    actual_closing_cash DECIMAL(12, 2) DEFAULT 0.00,
    expected_closing_online DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    actual_closing_online DECIMAL(12, 2) DEFAULT 0.00,
    opened_by_user_id BIGINT NOT NULL,
    closed_by_user_id BIGINT DEFAULT NULL,
    reconciled_by_user_id BIGINT DEFAULT NULL,
    reconciled_at TIMESTAMP NULL DEFAULT NULL,
    reconciliation_comment VARCHAR(500) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shift_shop_event (shop_id, event_id),
    INDEX idx_shift_status (status),
    INDEX idx_shift_active_lookup (shop_id, event_id, status, opened_at DESC)
);


CREATE TABLE shop_shift_denomination (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shift_session_id BIGINT NOT NULL,
    entry_type ENUM('OPENING', 'CLOSING', 'ADDITION') NOT NULL,
    currency_value INT NOT NULL,
    note_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_denomination_session
        FOREIGN KEY (shift_session_id)
        REFERENCES shop_shift_session(id)
        ON DELETE CASCADE
);

CREATE TABLE shop_invoice_sequence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    category_name VARCHAR(100) NOT NULL,
    next_seq INT NOT NULL DEFAULT 1,
    active_shift_id BIGINT,
    next_invoice_no VARCHAR(120) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_seq_shop_event UNIQUE (shop_id, event_id),
    INDEX idx_seq_shop_event (shop_id, event_id)
);


SET FOREIGN_KEY_CHECKS = 1;



