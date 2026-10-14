package com.nexawallet.platform.modules.transfer.entity;

/**
 * Lifecycle states of a financial transaction.
 */
public enum TransactionStatus {
    PENDING,
    SETTLED,
    FAILED,
    REVERSED
}
