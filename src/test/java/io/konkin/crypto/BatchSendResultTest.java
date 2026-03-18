package io.konkin.crypto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchSendResultTest {

    @Test
    void validConstruction() {
        BatchSendResult result = new BatchSendResult(
                Coin.BTC, "txid-abc",
                Map.of("addr1", new BigDecimal("0.5")),
                new BigDecimal("0.0001"),
                Map.of("key", "value"));

        assertEquals(Coin.BTC, result.coin());
        assertEquals("txid-abc", result.txId());
        assertEquals(new BigDecimal("0.5"), result.amountsByAddress().get("addr1"));
        assertEquals(new BigDecimal("0.0001"), result.totalFee());
        assertEquals("value", result.extras().get("key"));
    }

    @Test
    void nullExtras_defaultsToEmptyMap() {
        BatchSendResult result = new BatchSendResult(
                Coin.XMR, "txid-1",
                Map.of("addr1", BigDecimal.ONE),
                BigDecimal.ZERO, null);

        assertNotNull(result.extras());
        assertTrue(result.extras().isEmpty());
    }

    @Test
    void amountsByAddress_isImmutableCopy() {
        var amounts = new java.util.HashMap<String, BigDecimal>();
        amounts.put("addr1", BigDecimal.ONE);
        BatchSendResult result = new BatchSendResult(
                Coin.BTC, "txid-1", amounts, BigDecimal.ZERO, null);

        assertThrows(UnsupportedOperationException.class,
                () -> result.amountsByAddress().put("addr2", BigDecimal.TEN));
    }

    @Test
    void extras_isImmutableCopy() {
        var extras = new java.util.HashMap<String, String>();
        extras.put("k", "v");
        BatchSendResult result = new BatchSendResult(
                Coin.BTC, "txid-1",
                Map.of("addr1", BigDecimal.ONE),
                BigDecimal.ZERO, extras);

        assertThrows(UnsupportedOperationException.class,
                () -> result.extras().put("k2", "v2"));
    }

    @Test
    void nullCoin_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new BatchSendResult(null, "txid", Map.of(), BigDecimal.ZERO, null));
    }

    @Test
    void nullTxId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new BatchSendResult(Coin.BTC, null, Map.of(), BigDecimal.ZERO, null));
    }

    @Test
    void blankTxId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new BatchSendResult(Coin.BTC, "  ", Map.of(), BigDecimal.ZERO, null));
    }

    @Test
    void nullAmountsByAddress_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new BatchSendResult(Coin.BTC, "txid", null, BigDecimal.ZERO, null));
    }

    @Test
    void nullTotalFee_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new BatchSendResult(Coin.BTC, "txid", Map.of(), null, null));
    }
}
