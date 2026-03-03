package io.konkin.crypto;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletSnapshot(
        Coin coin,
        WalletStatus status,
        BigDecimal totalBalance,
        BigDecimal spendableBalance,
        Instant lastHeartbeat
) {}
