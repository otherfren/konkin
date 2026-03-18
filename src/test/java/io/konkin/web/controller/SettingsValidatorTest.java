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

    // ── spending-queue-mode ─────────────────────────────────────────────

    @Test
    void validateServer_spendingQueueMode_balanceRequired_noError() {
        Map<String, Object> values = Map.of("spending-queue-mode", "balance-required");
        assertNull(SettingsValidator.validateServer(values));
    }

    @Test
    void validateServer_spendingQueueMode_alwaysQueue_noError() {
        Map<String, Object> values = Map.of("spending-queue-mode", "always-queue");
        assertNull(SettingsValidator.validateServer(values));
    }

    @Test
    void validateServer_spendingQueueMode_invalidValue_error() {
        Map<String, Object> values = Map.of("spending-queue-mode", "invalid-mode");
        String error = SettingsValidator.validateServer(values);
        assertNotNull(error);
        assertTrue(error.contains("spending-queue-mode"));
    }

    @Test
    void validateServer_spendingQueueMode_nonStringValue_error() {
        Map<String, Object> values = Map.of("spending-queue-mode", 42);
        String error = SettingsValidator.validateServer(values);
        assertNotNull(error);
        assertTrue(error.contains("spending-queue-mode"));
    }

    // ── validateServer — additional fields ──────────────────────────────

    @Test
    void validateServer_validLogLevel_noError() {
        assertNull(SettingsValidator.validateServer(Map.of("log-level", "debug")));
    }

    @Test
    void validateServer_invalidLogLevel_error() {
        assertNotNull(SettingsValidator.validateServer(Map.of("log-level", "verbose")));
    }

    @Test
    void validateServer_logLevel_nonString_error() {
        assertNotNull(SettingsValidator.validateServer(Map.of("log-level", 42)));
    }

    @Test
    void validateServer_validLogRotateMaxSizeMb_noError() {
        assertNull(SettingsValidator.validateServer(Map.of("log-rotate-max-size-mb", 50)));
    }

    @Test
    void validateServer_logRotateMaxSizeMb_zero_error() {
        assertNotNull(SettingsValidator.validateServer(Map.of("log-rotate-max-size-mb", 0)));
    }

    @Test
    void validateServer_validHost_noError() {
        assertNull(SettingsValidator.validateServer(Map.of("host", "0.0.0.0")));
    }

    @Test
    void validateServer_blankHost_error() {
        assertNotNull(SettingsValidator.validateServer(Map.of("host", " ")));
    }

    @Test
    void validateServer_validLogFile_noError() {
        assertNull(SettingsValidator.validateServer(Map.of("log-file", "./logs/app.log")));
    }

    @Test
    void validateServer_blankLogFile_error() {
        assertNotNull(SettingsValidator.validateServer(Map.of("log-file", "")));
    }

    @Test
    void validateServer_validSecretsDir_noError() {
        assertNull(SettingsValidator.validateServer(Map.of("secrets-dir", "./secrets")));
    }

    @Test
    void validateServer_blankSecretsDir_error() {
        assertNotNull(SettingsValidator.validateServer(Map.of("secrets-dir", "")));
    }

    @Test
    void validateServer_unknownField_error() {
        String error = SettingsValidator.validateServer(Map.of("banana", "yes"));
        assertNotNull(error);
        assertTrue(error.contains("Unknown field"));
    }

    // ── validateDatabase ────────────────────────────────────────────────

    @Test
    void validateDatabase_validPoolSize_noError() {
        assertNull(SettingsValidator.validateDatabase(Map.of("pool-size", 10)));
    }

    @Test
    void validateDatabase_poolSizeTooLarge_error() {
        assertNotNull(SettingsValidator.validateDatabase(Map.of("pool-size", 999)));
    }

    @Test
    void validateDatabase_poolSizeZero_error() {
        assertNotNull(SettingsValidator.validateDatabase(Map.of("pool-size", 0)));
    }

    @Test
    void validateDatabase_unknownField_error() {
        assertNotNull(SettingsValidator.validateDatabase(Map.of("flavor", "postgres")));
    }

    @Test
    void validateDatabase_stringFields_noError() {
        assertNull(SettingsValidator.validateDatabase(Map.of("url", "jdbc:h2:mem", "user", "sa", "password", "pw")));
    }

    // ── validateWebUi ───────────────────────────────────────────────────

    @Test
    void validateWebUi_booleanFields_noError() {
        assertNull(SettingsValidator.validateWebUi(Map.of("password-protection.enabled", true)));
    }

    @Test
    void validateWebUi_nonBoolean_error() {
        assertNotNull(SettingsValidator.validateWebUi(Map.of("password-protection.enabled", "yes")));
    }

    @Test
    void validateWebUi_unknownField_error() {
        assertNotNull(SettingsValidator.validateWebUi(Map.of("theme", "dark")));
    }

    // ── validateRestApi ─────────────────────────────────────────────────

    @Test
    void validateRestApi_validEnabled_noError() {
        assertNull(SettingsValidator.validateRestApi(Map.of("enabled", true)));
    }

    @Test
    void validateRestApi_nonBoolean_error() {
        assertNotNull(SettingsValidator.validateRestApi(Map.of("enabled", "yes")));
    }

    @Test
    void validateRestApi_unknownField_error() {
        assertNotNull(SettingsValidator.validateRestApi(Map.of("rate-limit", 100)));
    }

    // ── validateTelegram ────────────────────────────────────────────────

    @Test
    void validateTelegram_validEnabled_noError() {
        assertNull(SettingsValidator.validateTelegram(Map.of("enabled", true)));
    }

    @Test
    void validateTelegram_validAutoDenyTimeout_noError() {
        assertNull(SettingsValidator.validateTelegram(Map.of("auto-deny-timeout", "5m")));
    }

    @Test
    void validateTelegram_iso8601Duration_noError() {
        assertNull(SettingsValidator.validateTelegram(Map.of("auto-deny-timeout", "PT5M")));
    }

    @Test
    void validateTelegram_invalidDuration_error() {
        assertNotNull(SettingsValidator.validateTelegram(Map.of("auto-deny-timeout", "banana")));
    }

    @Test
    void validateTelegram_nonStringDuration_error() {
        assertNotNull(SettingsValidator.validateTelegram(Map.of("auto-deny-timeout", 5)));
    }

    @Test
    void validateTelegram_blankApiBaseUrl_error() {
        assertNotNull(SettingsValidator.validateTelegram(Map.of("api-base-url", "")));
    }

    @Test
    void validateTelegram_unknownField_error() {
        assertNotNull(SettingsValidator.validateTelegram(Map.of("token", "secret")));
    }

    // ── validateAgent ───────────────────────────────────────────────────

    @Test
    void validateAgent_validFields_noError() {
        assertNull(SettingsValidator.validateAgent(Map.of("visible", true, "port", 9090, "bind", "127.0.0.1")));
    }

    @Test
    void validateAgent_nonBooleanVisible_error() {
        assertNotNull(SettingsValidator.validateAgent(Map.of("visible", "yes")));
    }

    @Test
    void validateAgent_portOutOfRange_error() {
        assertNotNull(SettingsValidator.validateAgent(Map.of("port", 70000)));
    }

    @Test
    void validateAgent_blankBind_error() {
        assertNotNull(SettingsValidator.validateAgent(Map.of("bind", "")));
    }

    @Test
    void validateAgent_unknownField_error() {
        assertNotNull(SettingsValidator.validateAgent(Map.of("threads", 4)));
    }

    // ── validateDebug ───────────────────────────────────────────────────

    @Test
    void validateDebug_validBooleans_noError() {
        assertNull(SettingsValidator.validateDebug(Map.of("enabled", true, "seed-fake-data", false)));
    }

    @Test
    void validateDebug_nonBoolean_error() {
        assertNotNull(SettingsValidator.validateDebug(Map.of("enabled", "yes")));
    }

    @Test
    void validateDebug_unknownField_error() {
        assertNotNull(SettingsValidator.validateDebug(Map.of("verbose", true)));
    }

    // ── validateCoin — rule validation ──────────────────────────────────

    @Test
    void validateCoin_validAutoAcceptRule_noError() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "value-lt", "value", 0.01));
        assertNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", rules)));
    }

    @Test
    void validateCoin_invalidRuleType_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "invalid-type", "value", 1.0));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-deny", rules)));
    }

    @Test
    void validateCoin_ruleValueZero_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "value-gt", "value", 0));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-deny", rules)));
    }

    @Test
    void validateCoin_ruleValueNegative_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "value-gt", "value", -1.0));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", rules)));
    }

    @Test
    void validateCoin_ruleValueAsString_noError() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "value-gt", "value", "5.5"));
        assertNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", rules)));
    }

    @Test
    void validateCoin_ruleValueNotANumber_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "value-gt", "value", "abc"));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", rules)));
    }

    @Test
    void validateCoin_ruleValueWrongType_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "value-gt", "value", true));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", rules)));
    }

    @Test
    void validateCoin_cumulatedRuleWithPeriod_noError() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "cumulated-value-gt", "value", 10.0, "period", "24h"));
        assertNull(SettingsValidator.validateCoin(Map.of("auth.auto-deny", rules)));
    }

    @Test
    void validateCoin_cumulatedRuleMissingPeriod_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "cumulated-value-gt", "value", 10.0));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-deny", rules)));
    }

    @Test
    void validateCoin_cumulatedRuleInvalidPeriod_error() {
        List<Map<String, Object>> rules = List.of(Map.of("type", "cumulated-value-lt", "value", 5.0, "period", "banana"));
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", rules)));
    }

    @Test
    void validateCoin_ruleNotAList_error() {
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", "not-a-list")));
    }

    @Test
    void validateCoin_ruleItemNotAMap_error() {
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.auto-accept", List.of("not-a-map"))));
    }

    @Test
    void validateCoin_validMinApprovals_noError() {
        assertNull(SettingsValidator.validateCoin(Map.of("auth.min-approvals-required", 3)));
    }

    @Test
    void validateCoin_minApprovalsZero_error() {
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.min-approvals-required", 0)));
    }

    @Test
    void validateCoin_validVetoChannels_noError() {
        assertNull(SettingsValidator.validateCoin(Map.of("auth.veto-channels", List.of("telegram"))));
    }

    @Test
    void validateCoin_vetoChannelsNotAList_error() {
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.veto-channels", "telegram")));
    }

    @Test
    void validateCoin_vetoChannelsBlankEntry_error() {
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.veto-channels", List.of(""))));
    }

    @Test
    void validateCoin_validMcpAuthChannels_noError() {
        assertNull(SettingsValidator.validateCoin(Map.of("auth.mcp-auth-channels", List.of("agent-1"))));
    }

    @Test
    void validateCoin_authBooleans_noError() {
        assertNull(SettingsValidator.validateCoin(Map.of("auth.web-ui", true, "auth.rest-api", false, "auth.telegram", true)));
    }

    @Test
    void validateCoin_authBooleanNonBoolean_error() {
        assertNotNull(SettingsValidator.validateCoin(Map.of("auth.web-ui", "yes")));
    }
}
