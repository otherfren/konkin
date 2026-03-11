/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.web.controller;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsValidatorTest {

    // ── validateConnectionForm — Bitcoin ─────────────────────────────────

    @Test
    void bitcoinConnectionForm_validParams_noErrors() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rpcHost", "127.0.0.1");
        params.put("rpcPort", "8332");
        params.put("rpcUser", "rpcuser");
        params.put("rpcPassword", "rpcpassword");
        params.put("walletName", "");

        List<String> errors = SettingsValidator.validateConnectionForm("bitcoin", params);
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void bitcoinConnectionForm_missingHost_error() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rpcHost", "");
        params.put("rpcPort", "8332");
        params.put("rpcUser", "user");
        params.put("rpcPassword", "pass");

        List<String> errors = SettingsValidator.validateConnectionForm("bitcoin", params);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Host"));
    }

    @Test
    void bitcoinConnectionForm_invalidPort_error() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rpcHost", "127.0.0.1");
        params.put("rpcPort", "99999");
        params.put("rpcUser", "user");
        params.put("rpcPassword", "pass");

        List<String> errors = SettingsValidator.validateConnectionForm("bitcoin", params);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Port"));
    }

    @Test
    void bitcoinConnectionForm_nonNumericPort_error() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rpcHost", "127.0.0.1");
        params.put("rpcPort", "abc");
        params.put("rpcUser", "user");
        params.put("rpcPassword", "pass");

        List<String> errors = SettingsValidator.validateConnectionForm("bitcoin", params);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("number"));
    }

    @Test
    void bitcoinConnectionForm_missingUserAndPassword_twoErrors() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rpcHost", "127.0.0.1");
        params.put("rpcPort", "8332");
        params.put("rpcUser", "");
        params.put("rpcPassword", "");

        List<String> errors = SettingsValidator.validateConnectionForm("bitcoin", params);
        assertEquals(2, errors.size());
    }

    // ── validateConnectionForm — Litecoin ────────────────────────────────

    @Test
    void litecoinConnectionForm_validParams_noErrors() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rpcHost", "127.0.0.1");
        params.put("rpcPort", "9332");
        params.put("rpcUser", "ltcuser");
        params.put("rpcPassword", "ltcpass");
        params.put("walletName", "mywallet");

        List<String> errors = SettingsValidator.validateConnectionForm("litecoin", params);
        assertTrue(errors.isEmpty());
    }

    // ── validateConnectionForm — Monero ──────────────────────────────────

    @Test
    void moneroConnectionForm_validParams_noErrors() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("daemonHost", "127.0.0.1");
        params.put("daemonPort", "18081");
        params.put("daemonUser", "");
        params.put("daemonPassword", "");
        params.put("walletRpcHost", "127.0.0.1");
        params.put("walletRpcPort", "18083");
        params.put("walletRpcUser", "rpcuser");
        params.put("walletRpcPassword", "rpcpass");

        List<String> errors = SettingsValidator.validateConnectionForm("monero", params);
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void moneroConnectionForm_missingWalletRpcCredentials_errors() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("daemonHost", "127.0.0.1");
        params.put("daemonPort", "18081");
        params.put("daemonUser", "");
        params.put("daemonPassword", "");
        params.put("walletRpcHost", "127.0.0.1");
        params.put("walletRpcPort", "18083");
        params.put("walletRpcUser", "");
        params.put("walletRpcPassword", "");

        List<String> errors = SettingsValidator.validateConnectionForm("monero", params);
        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Wallet RPC User")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Wallet RPC Password")));
    }

    @Test
    void moneroConnectionForm_invalidPorts_errors() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("daemonHost", "127.0.0.1");
        params.put("daemonPort", "0");
        params.put("daemonUser", "");
        params.put("daemonPassword", "");
        params.put("walletRpcHost", "127.0.0.1");
        params.put("walletRpcPort", "70000");
        params.put("walletRpcUser", "user");
        params.put("walletRpcPassword", "pass");

        List<String> errors = SettingsValidator.validateConnectionForm("monero", params);
        assertEquals(2, errors.size());
    }

    // ── validateConnectionForm — Unknown coin ────────────────────────────

    @Test
    void unknownCoin_error() {
        Map<String, String> params = new LinkedHashMap<>();
        List<String> errors = SettingsValidator.validateConnectionForm("dogecoin", params);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Unknown coin"));
    }

    // ── validateCoin — existing validation ───────────────────────────────

    @Test
    void validateCoin_validEnabled_noError() {
        Map<String, Object> values = Map.of("enabled", true);
        assertNull(SettingsValidator.validateCoin(values));
    }

    @Test
    void validateCoin_invalidEnabled_error() {
        Map<String, Object> values = Map.of("enabled", "notboolean");
        assertNotNull(SettingsValidator.validateCoin(values));
    }

    @Test
    void validateCoin_unknownField_error() {
        Map<String, Object> values = Map.of("unknown-field", true);
        String error = SettingsValidator.validateCoin(values);
        assertNotNull(error);
        assertTrue(error.contains("Unknown field"));
    }

    @Test
    void validateServer_validPort_noError() {
        Map<String, Object> values = Map.of("port", 7070);
        assertNull(SettingsValidator.validateServer(values));
    }

    @Test
    void validateServer_portOutOfRange_error() {
        Map<String, Object> values = Map.of("port", 70000);
        assertNotNull(SettingsValidator.validateServer(values));
    }
}
