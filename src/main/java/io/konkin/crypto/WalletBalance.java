package io.konkin.crypto;

import java.math.BigDecimal;

public record WalletBalance(Coin coin, BigDecimal total, BigDecimal spendable) {
    public WalletBalance {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (total == null) throw new IllegalArgumentException("total must not be null");
        if (spendable == null) throw new IllegalArgumentException("spendable must not be null");
    }
}
