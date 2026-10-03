-- ==========================================
-- SMART PARKING SYSTEM — SCHEMA
-- ==========================================
-- This project shares one physical MySQL database with the
-- SaveSmart project (the free-tier Clever Cloud add-on only
-- provides one database). To avoid any collision with SaveSmart's
-- tables (save_users, save_goals, save_transactions), every table
-- here is prefixed with "park_". This script does NOT drop or
-- touch any table that doesn't start with that prefix.
--
-- Run this ONCE against your MySQL server (via DBeaver, phpMyAdmin,
-- MySQL Workbench, the `mysql` CLI, or Main.java's connection),
-- using the SAME database SaveSmart already uses.

DROP TABLE IF EXISTS park_users;
DROP TABLE IF EXISTS park_slots;

-- ------------------------------------------
-- PARK_USERS
-- ------------------------------------------
CREATE TABLE park_users (
    user_id       INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(64)  NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ------------------------------------------
-- PARK_SLOTS
-- slot_type: 'CAR', 'BIKE', or 'ACCESSIBLE'
-- status: 'AVAILABLE' or 'RESERVED'
-- ------------------------------------------
CREATE TABLE park_slots (
    slot_id         INT AUTO_INCREMENT PRIMARY KEY,
    slot_number     VARCHAR(20) NOT NULL UNIQUE,
    slot_type       VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    user_email      VARCHAR(100),
    user_name       VARCHAR(100),
    vehicle_number  VARCHAR(20),
    vehicle_type    VARCHAR(20),
    disability_type VARCHAR(50),
    expiry_time     DATETIME
);

-- ------------------------------------------
-- SEED SLOTS
-- Matches the frontend's displayed counts:
-- 20 car, 30 bike/scooty, 10 accessible
-- ------------------------------------------

-- Car slots: C1 .. C20
INSERT INTO park_slots (slot_number, slot_type)
SELECT CONCAT('C', n), 'CAR'
FROM (
    SELECT ROW_NUMBER() OVER () AS n
    FROM information_schema.columns
    LIMIT 20
) nums;

-- Bike/scooty slots: B1 .. B30
INSERT INTO park_slots (slot_number, slot_type)
SELECT CONCAT('B', n), 'BIKE'
FROM (
    SELECT ROW_NUMBER() OVER () AS n
    FROM information_schema.columns
    LIMIT 30
) nums;

-- Accessible slots: A1 .. A10
INSERT INTO park_slots (slot_number, slot_type)
SELECT CONCAT('A', n), 'ACCESSIBLE'
FROM (
    SELECT ROW_NUMBER() OVER () AS n
    FROM information_schema.columns
    LIMIT 10
) nums;

CREATE INDEX idx_slots_email ON park_slots(user_email);
