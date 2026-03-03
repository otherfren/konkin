package io.konkin.crypto;

import java.math.BigDecimal;

public final class WalletInsufficientFundsException extends WalletOperationException {

    private final BigDecimal requested;
    private final BigDecimal available;

    public WalletInsufficientFundsException(BigDecimal requested, BigDecimal available) {
        super("Insufficient funds: requested " + requested + " but only " + available + " available");
        this.requested = requested;
        this.available = available;
    }

    public BigDecimal requested() {
        return requested;
    }

    public BigDecimal available() {
        return available;
    }
}
