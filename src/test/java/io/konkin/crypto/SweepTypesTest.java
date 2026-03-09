package io.konkin.crypto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SweepTypesTest {

    // ---- SweepRequest ----

    @Test
    void sweepRequest_validConstruction() {
        var req = new SweepRequest(Coin.BTC, "bc1qaddr", Map.of("memo", "test"));
        assertEquals(Coin.BTC, req.coin());
        assertEquals("bc1qaddr", req.toAddress());
        assertEquals(Map.of("memo", "test"), req.extras());
    }

    @Test
    void sweepRequest_nullCoinThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SweepRequest(null, "bc1qaddr", null));
    }

    @Test
    void sweepRequest_blankAddressThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SweepRequest(Coin.BTC, "  ", null));
    }

    @Test
    void sweepRequest_nullExtrasDefaultsToEmptyMap() {
        var req = new SweepRequest(Coin.BTC, "bc1qaddr", null);
        assertNotNull(req.extras());
        assertTrue(req.extras().isEmpty());
    }

    // ---- SweepResult ----

    @Test
    void sweepResult_validConstruction() {
        var res = new SweepResult(Coin.LTC, List.of("tx1"), BigDecimal.ONE, BigDecimal.ZERO, null);
        assertEquals(Coin.LTC, res.coin());
        assertEquals(List.of("tx1"), res.txIds());
        assertEquals(BigDecimal.ONE, res.totalAmount());
        assertEquals(BigDecimal.ZERO, res.totalFee());
        assertTrue(res.extras().isEmpty());
    }

    @Test
    void sweepResult_nullCoinThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SweepResult(null, List.of("tx1"), BigDecimal.ONE, BigDecimal.ZERO, null));
    }

    @Test
    void sweepResult_emptyTxIdsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SweepResult(Coin.BTC, List.of(), BigDecimal.ONE, BigDecimal.ZERO, null));
    }

    @Test
    void sweepResult_nullTotalAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SweepResult(Coin.BTC, List.of("tx1"), null, BigDecimal.ZERO, null));
    }

    @Test
    void sweepResult_txIdsAreDefensivelyCopied() {
        var mutable = new ArrayList<>(List.of("tx1", "tx2"));
        var res = new SweepResult(Coin.BTC, mutable, BigDecimal.TEN, BigDecimal.ONE, null);
        mutable.add("tx3");
        assertEquals(2, res.txIds().size());
        assertThrows(UnsupportedOperationException.class, () -> res.txIds().add("tx4"));
    }
}
