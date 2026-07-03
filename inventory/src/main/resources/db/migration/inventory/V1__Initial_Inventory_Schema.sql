
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS stock_movement;
DROP TABLE IF EXISTS counter_stocks;
DROP TABLE IF EXISTS sales;
DROP TABLE IF EXISTS inventory_stocks;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS supplier;
DROP TABLE IF EXISTS category;

SET FOREIGN_KEY_CHECKS = 1;


CREATE TABLE category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);


CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    sku VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    mrp DECIMAL(12, 2) NOT NULL,
    selling_price DECIMAL(12, 2) NOT NULL,
    discount DECIMAL(5, 2),
    min_threshold INT NOT NULL DEFAULT 10,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id),
    INDEX idx_product_name (name),
    INDEX idx_product_price (selling_price),
    INDEX idx_product_category (category_id),
    INDEX idx_product_category_active (category_id, is_active),
    INDEX idx_product_category_active_created (category_id, is_active, created_at),
    INDEX idx_product_active_created (is_active, created_at)
);



CREATE TABLE inventory_stocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    location VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_inventory_stocks_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uq_inventory_stocks_product_event_loc UNIQUE (product_id, event_id, location),
    CONSTRAINT chk_inventory_stocks_quantity CHECK (quantity >= 0),
    INDEX idx_inventory_stocks_product (product_id),
    INDEX idx_inventory_stocks_location (location)
);


CREATE TABLE counter_stocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    seller_user VARCHAR(255) NOT NULL,
    shop_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    initial_quantity INT NOT NULL DEFAULT 0,
    live_quantity INT NOT NULL DEFAULT 0,
    sale_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_counter_stocks_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uq_counter_stocks_prod_shop_event UNIQUE (product_id, shop_id, event_id),
    INDEX idx_counter_stocks_seller (seller_user),
    INDEX idx_counter_stocks_product (product_id),
    INDEX idx_counter_stocks_date (sale_date)
);


CREATE TABLE stock_movement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    movement_type ENUM('IN', 'OUT', 'ADJUSTMENT', 'TRANSFER') NOT NULL,
    quantity INT NOT NULL,
    reason VARCHAR(255),
    location_from VARCHAR(100) NULL,
    location_to VARCHAR(100) NULL,
    shop_id BIGINT NULL,
    movement_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_sm_product FOREIGN KEY (product_id) REFERENCES product(id),
    INDEX idx_sm_product (product_id),
    INDEX idx_sm_username (username),
    INDEX idx_sm_date (movement_date),
    INDEX idx_sm_type (movement_type),
    INDEX idx_sm_product_event (product_id, event_id),
    INDEX idx_sm_locations (location_from, location_to)
);







