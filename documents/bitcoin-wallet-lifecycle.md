# Bitcoin Wallet Lifecycle — Full Control Flow Plan

## Goal

Wire `BitcoinWallet` into the KONKIN server so that:
- A single `BitcoinWallet` is created at startup from the existing secret files
- A background supervisor monitors the connection, loads the wallet, reconnects, and polls sync status
- All RPC calls are serialized (no parallel calls to Bitcoin Core)
- `/wallets` shows live connection data (status, balance, last heartbeat) — only for connected coins
- The `send_coin` MCP tool executes approved sends through `BitcoinWallet.send()`
- Graceful shutdown closes the wallet connection cleanly

---

## Inventory of What Exists

| Component                  | Location                                           | Current State                                                                                             |
|----------------------------|----------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Bitcoin daemon secret file | `config.bitcoin().bitcoinDaemonConfigSecretFile()` | Parsed at startup by `SecretFileBootstrapper`; contains `rpcuser`, `rpcpassword`, `rpcconnect`, `rpcport` |
| Bitcoin wallet secret file | `config.bitcoin().bitcoinWalletConfigSecretFile()` | Parsed at startup; contains `wallet` (name), `wallet-passphrase`                                          |
| `BitcoinWallet`            | `io.konkin.crypto.bitcoin`                         | Fully implemented — wraps cj-btc-jsonrpc `BitcoinClient`                                                  |
| `WalletConnectionConfig`   | `io.konkin.crypto`                                 | Record: `Coin`, `rpcUrl`, `username`, `password`, `extras` map                                            |
| `BitcoinExtras`            | `io.konkin.crypto.bitcoin`                         | Keys: `NETWORK`, `WALLET_NAME`, `FEE_POLICY`, `FEE_CAP_NATIVE`, `MEMO`                                    |
| `WalletStatus` enum        | `io.konkin.crypto`                                 | `AVAILABLE`, `SYNCING`, `OFFLINE`                                                                         |
| `/wallets` page            | `LandingPageMapper.buildCoinAuthDefinition()`      | `connectionStatus`, `lastLifeSign`, `maskedBalance` are hardcoded to `"unknown"`                          |
| `SendCoinTool`             | `io.konkin.agent.mcp.driver`                       | Creates `ApprovalRequestRow` in state `QUEUED`; no execution after `APPROVED`                             |
| MCP wallet query tools     | `io.konkin.agent.mcp.driver` (planned)             | Not yet created — 6 tools to expose all `CoinWallet` methods via MCP                                      |
| `ExecutionAttemptDetail`   | `io.konkin.db.entity`                              | DB record exists for tracking execution; table is ready                                                   |
| Approval flow              | `VoteOnApprovalTool` / `LandingPageController`     | Transitions QUEUED → PENDING → APPROVED; stops there                                                      |
| App shutdown hook          | `App.java`                                         | Calls `webServer.stop()` then `dbManager.shutdown()`                                                      |

---

## Architecture Overview

```
┌─────────────┐     reads secrets   ┌───────────────────────┐
│  App.main() │ ──────────────────► │ WalletConnectionConfig│
└──────┬──────┘                     └───────────┬───────────┘
       │ creates                                │
       ▼                                        ▼
┌──────────────────┐    owns     ┌──────────────────────────┐
│ KonkinWebServer  │ ──────────► │  WalletSupervisor        │
└──────────────────┘             │  (new class, daemon thd) │
                                 │  - holds BitcoinWallet   │
                                 │  - serializes all RPC    │
                                 │  - reconnect loop        │
                                 │  - sync polling          │
                                 │  - exposes snapshot()    │
                                 │  - exposes execute(fn)   │
                                 └────────────┬─────────────┘
                                              │
              ┌───────────────────────────────┼──────────────────┐
              ▼                               ▼                  ▼
       LandingPageMapper              MCP Driver Tools      App shutdown
       reads snapshot()               (via execute(fn))     calls close()
                                            │
                      ┌──────────┬──────────┼──────────┬──────────┬──────────┐
                      ▼          ▼          ▼          ▼          ▼          ▼
               wallet_status  wallet_   deposit_   pending_   sign_     verify_
                              balance   address    txns       message   message
                                            │
                              ┌─────────────┘
                              ▼
                     TransactionExecutionService
                     (polls APPROVED → calls send())
```

---

## Files to Create

### 1. `io.konkin.crypto.WalletSnapshot` (record)

Immutable point-in-time view of a wallet's state, safe to read from any thread.

```java
package io.konkin.crypto;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletSnapshot(
    Coin coin,
    WalletStatus status,
    BigDecimal totalBalance,
    BigDecimal spendableBalance,
    Instant lastHeartbeat    // last successful RPC call
) {}
```

### 2. `io.konkin.crypto.WalletSupervisor` (new class)

Single background daemon thread that owns the wallet lifecycle. **All RPC interaction goes through this class.**

**Fields:**
- `BitcoinWallet wallet` — the actual wallet (created once, recreated on hard reconnect)
- `WalletConnectionConfig config` — connection params
- `volatile WalletSnapshot snapshot` — latest state, atomically published
- `ExecutorService rpcExecutor` — single-thread executor that serializes all RPC calls
- `ScheduledExecutorService scheduler` — for periodic heartbeat/sync checks
- `volatile boolean running`

**Public API:**
- `start()` — start the supervisor; kicks off loadWallet + heartbeat loop
- `close()` — graceful shutdown: cancel scheduler, drain rpcExecutor, set OFFLINE
- `snapshot()` — returns current `WalletSnapshot` (non-blocking, volatile read)
- `<T> T execute(Function<BitcoinWallet, T> action)` — submit a wallet action to the serial executor; blocks caller until done; throws `WalletConnectionException` if OFFLINE

**Internal lifecycle (on the scheduler, every 30s):**
1. Try `wallet.status()` via rpcExecutor
2. If succeeds → update snapshot with status + balance + `Instant.now()` heartbeat
3. If `OFFLINE` or exception →
   a. Try `loadwallet` RPC (wallet may not be loaded after node restart)
   b. If still fails → rebuild `BitcoinClient` (full reconnect)
   c. Update snapshot to `OFFLINE`
4. Log state transitions (AVAILABLE→OFFLINE, OFFLINE→SYNCING, etc.)

**RPC serialization contract:**
- `rpcExecutor` is a `Executors.newSingleThreadExecutor()` — guarantees all calls are sequential
- Public `execute()` submits a `Callable` to this executor and blocks on `.get()`
- The heartbeat loop also submits to `rpcExecutor`, never calling the wallet directly
- This means heartbeats and user calls (send, balance, etc.) are naturally serialized

**Wallet loading:**
- On start and after reconnect, call `loadwallet` RPC via `BitcoinClient.send("loadwallet", ...)` with the wallet name from config
- Ignore "already loaded" errors (JSON-RPC error code -35)
- If wallet doesn't exist, log error and stay OFFLINE

### 3. `io.konkin.crypto.WalletSecretLoader` (new utility class)

Parses the existing Bitcoin secret files into a `WalletConnectionConfig`.

```java
package io.konkin.crypto;

public final class WalletSecretLoader {
    /** Reads daemon + wallet secret files and builds a WalletConnectionConfig. */
    public static WalletConnectionConfig loadBitcoin(String daemonSecretPath, String walletSecretPath) { ... }
}
```

Reads the properties-style files that `SecretFileBootstrapper` creates:
- From daemon file: `rpcuser`, `rpcpassword`, `rpcconnect`, `rpcport`
- From wallet file: `wallet` (name), `wallet-passphrase`
- Builds `rpcUrl` as `http://{rpcconnect}:{rpcport}`
- Puts `wallet` → `BitcoinExtras.WALLET_NAME`, detects network from port (8332=mainnet, 18332/18443=testnet, etc.) or defaults to mainnet
- Extras: `NETWORK` derived from port or config

---

## Files to Modify

### 4. `App.java` — add wallet shutdown

In the shutdown hook, after `webServer.stop()`, call `webServer.walletSupervisor().close()` (or have `webServer.stop()` handle it internally).

**Change:** No change needed if `KonkinWebServer.stop()` closes the supervisor (preferred — keeps ownership clean).

### 5. `KonkinWebServer` — create and own the supervisor

**In `start()`**, after DB init but before agent endpoints:

```java
WalletSupervisor walletSupervisor = null;
if (config.bitcoin().enabled()) {
    WalletConnectionConfig btcConfig = WalletSecretLoader.loadBitcoin(
        config.bitcoin().bitcoinDaemonConfigSecretFile(),
        config.bitcoin().bitcoinWalletConfigSecretFile()
    );
    walletSupervisor = new WalletSupervisor(btcConfig);
    walletSupervisor.start();
}
```

- Store as field `private WalletSupervisor walletSupervisor`
- Pass to `LandingPageMapper` (for `/wallets` page)
- Pass to `McpAgentServer` / `SendCoinTool` (for execution)
- In `stop()`: call `walletSupervisor.close()` before stopping Javalin

### 6. `LandingPageMapper` — show live wallet data

**Constructor change:** Accept `WalletSupervisor` (nullable).

**In `buildCoinAuthDefinition()`** (lines 651-658):
- Replace hardcoded `"unknown"` values:

```java
if (walletSupervisor != null && "bitcoin".equals(coinId)) {
    WalletSnapshot snap = walletSupervisor.snapshot();
    coin.put("connectionStatus", snap.status().name().toLowerCase());
    coin.put("lastLifeSign", snap.lastHeartbeat() == null ? "never" : formatInstant(snap.lastHeartbeat()));
    coin.put("maskedBalance", snap.totalBalance() == null ? "unknown" : formatBalance(snap.totalBalance()));
} else {
    coin.put("connectionStatus", coinConfig.enabled() ? "not connected" : "disabled");
    coin.put("lastLifeSign", "n/a");
    coin.put("maskedBalance", "n/a");
}
```

**In `buildWalletsModel()`** (lines 558-567):
- Only include coins that are either connected or enabled:

```java
List<Map<String, Object>> coins = new ArrayList<>();
if (config.bitcoin().enabled()) {
    coins.add(buildCoinAuthDefinition("bitcoin", config.bitcoin()));
}
// litecoin and monero only if enabled (no wallet impl yet)
if (config.litecoin().enabled()) {
    coins.add(buildCoinAuthDefinition("litecoin", config.litecoin()));
}
if (config.monero().enabled()) {
    coins.add(buildCoinAuthDefinition("monero", config.monero()));
}
```

### 7. `SendCoinTool` — execute approved transactions

Currently `SendCoinTool` only creates an `ApprovalRequestRow` in `QUEUED` state. Transaction execution after approval is **not implemented anywhere** in the codebase.

Two approaches — pick the simpler one that fits the existing architecture:

**Approach: Polling execution service (new background service)**

Create `io.konkin.crypto.TransactionExecutionService`:
- Runs on a scheduled executor (every 5s)
- Queries DB for requests in `APPROVED` state
- For each approved request:
  1. Transition state to `EXECUTING`
  2. Build `SendRequest` from the `ApprovalRequestRow` fields
  3. Call `walletSupervisor.execute(wallet -> wallet.send(request))`
  4. On success: insert `ExecutionAttemptDetail` with txid + fee, transition to `COMPLETED`
  5. On `WalletInsufficientFundsException`: transition to `FAILED` with reason
  6. On `WalletConnectionException`: transition to `FAILED` (or retry with backoff)
  7. On `WalletOperationException`: transition to `FAILED`

**Wire into `KonkinWebServer`:**
- Create after `walletSupervisor.start()`
- Start with `executionService.start()`
- Stop in `stop()` before closing supervisor

### 8. `McpAgentServer` / `SendCoinTool` — pass supervisor reference

`SendCoinTool.create()` currently takes `requestRepo`, `historyRepo`, `runtimeConfig`. No changes needed to `SendCoinTool` itself if we use the polling execution service (approach above), since execution is decoupled from the MCP tool.

If instead we wanted inline execution (send immediately on approval), we'd modify `VoteOnApprovalTool` — but the polling service is cleaner and matches the existing pattern of `ApprovalExpiryService`.

---

## Implementation Order

| Step | What                               | Files                                                                                                                            | Depends On              |
|------|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|-------------------------|
| 1    | `WalletSnapshot` record            | `io.konkin.crypto.WalletSnapshot`                                                                                                | nothing                 |
| 2    | `WalletSecretLoader`               | `io.konkin.crypto.WalletSecretLoader`                                                                                            | nothing                 |
| 3    | `WalletSupervisor`                 | `io.konkin.crypto.WalletSupervisor`                                                                                              | step 1, `BitcoinWallet` |
| 4    | Wire into `KonkinWebServer`        | modify `KonkinWebServer.java`                                                                                                    | steps 2-3               |
| 5    | Wire into `LandingPageMapper`      | modify `LandingPageMapper.java`                                                                                                  | step 4                  |
| 6    | `TransactionExecutionService`      | `io.konkin.crypto.TransactionExecutionService`                                                                                   | step 3                  |
| 7    | Wire execution service             | modify `KonkinWebServer.java`                                                                                                    | step 6                  |
| 8    | `WalletToolSupport` helper         | `io.konkin.agent.mcp.driver.WalletToolSupport`                                                                                   | step 3                  |
| 9    | MCP wallet tools (6 tools)         | `WalletStatusTool`, `WalletBalanceTool`, `DepositAddressTool`, `PendingTransactionsTool`, `SignMessageTool`, `VerifyMessageTool` | step 8                  |
| 10   | Register tools in `McpAgentServer` | modify `McpAgentServer.java`                                                                                                     | steps 4, 9              |
| 11   | Graceful shutdown                  | verify `KonkinWebServer.stop()`                                                                                                  | steps 4, 7, 10          |
| 12   | Compile + integration test         | `mvn compile`, manual test with testnet4                                                                                         | all                     |

---

## Serialization Detail

```
          ┌─────────────────────────────┐
          │   SingleThreadExecutor      │
          │   (rpcExecutor)             │
          │                             │
          │  ┌───┐ ┌───┐ ┌───┐ ┌───┐   │
 submit → │  │ H │ │ S │ │ H │ │ B │   │  ← queue of tasks
          │  └───┘ └───┘ └───┘ └───┘   │
          │        executes one         │
          │        at a time            │
          └─────────────────────────────┘
  H = heartbeat    S = send    B = balance    Q = query (status/deposit/sign/verify/pending)
```

- Heartbeat submits to rpcExecutor every 30s
- `execute(fn)` from TransactionExecutionService submits to same rpcExecutor
- `execute(fn)` from MCP wallet tools (wallet_status, wallet_balance, deposit_address, pending_transactions, sign_message, verify_message) submits to same rpcExecutor
- No two RPC calls ever run in parallel
- Timeout on `Future.get()` prevents deadlock (e.g., 60s timeout)

---

## Graceful Shutdown Sequence

```
App shutdown hook
  └─► webServer.stop()
        ├─► stopAgentEndpoints()          // stop MCP servers
        ├─► approvalExpiryService.stop()  // stop expiry checker
        ├─► executionService.stop()       // stop execution poller (NEW)
        ├─► walletSupervisor.close()      // stop heartbeat, drain rpcExecutor (NEW)
        │     ├─► scheduler.shutdown()
        │     ├─► rpcExecutor.shutdown()
        │     ├─► rpcExecutor.awaitTermination(10s)
        │     └─► set snapshot to OFFLINE
        ├─► landingResourceWatcher.stop()
        └─► app.stop()                   // stop Javalin
```

---

## MCP Wallet Tools (Driver Agent)

All wallet library functions are exposed as MCP tools on the driver agent. Each tool calls through `walletSupervisor.execute(fn)` to guarantee RPC serialization. Tools that only read state (status, balance, pending transactions) execute immediately — no approval flow. `send_coin` remains the only tool routed through the approval pipeline.

Each tool class follows the existing pattern: static `create()` factory returning `SyncToolSpecification`, JSON-serialized responses via `CallToolResult`.

---

### Tool 1: `wallet_status` (read-only)

**Library method:** `CoinWallet.status()` → `WalletStatus`

**Description:** Check the synchronization status of a connected wallet.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "coin": { "type": "string", "description": "Coin identifier: bitcoin" }
  },
  "required": ["coin"]
}
```

**Behavior:**
1. Validate coin is enabled
2. Call `walletSupervisor.execute(wallet -> wallet.status())`
3. Return status enum value

**Response:**
```json
{ "coin": "bitcoin", "status": "AVAILABLE" }
```

**Error cases:** `WalletConnectionException` → `{"error": "wallet_offline", "message": "..."}`

**File:** `io.konkin.agent.mcp.driver.WalletStatusTool`

---

### Tool 2: `wallet_balance` (read-only)

**Library method:** `CoinWallet.balance()` → `WalletBalance(Coin coin, BigDecimal total, BigDecimal spendable)`

**Description:** Get the total and spendable balance of a connected wallet.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "coin": { "type": "string", "description": "Coin identifier: bitcoin" }
  },
  "required": ["coin"]
}
```

**Behavior:**
1. Validate coin is enabled
2. Call `walletSupervisor.execute(wallet -> wallet.balance())`
3. Return balance record as JSON

**Response:**
```json
{ "coin": "BTC", "total": "1.23456789", "spendable": "1.20000000" }
```

**File:** `io.konkin.agent.mcp.driver.WalletBalanceTool`

---

### Tool 3: `deposit_address` (read-only)

**Library method:** `CoinWallet.depositAddress()` → `DepositAddress(Coin coin, String address, Map<String, String> extras)`

**Description:** Generate a new deposit address for receiving funds.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "coin": { "type": "string", "description": "Coin identifier: bitcoin" }
  },
  "required": ["coin"]
}
```

**Behavior:**
1. Validate coin is enabled
2. Call `walletSupervisor.execute(wallet -> wallet.depositAddress())`
3. Return address record as JSON

**Response:**
```json
{ "coin": "BTC", "address": "bc1q...", "extras": {} }
```

**File:** `io.konkin.agent.mcp.driver.DepositAddressTool`

---

### Tool 4: `pending_transactions` (read-only)

**Library methods:** `CoinWallet.pendingIncoming()` and `CoinWallet.pendingOutgoing()` → `List<Transaction>`

**Description:** List pending (unconfirmed) incoming and/or outgoing transactions.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "coin": { "type": "string", "description": "Coin identifier: bitcoin" },
    "direction": { "type": "string", "description": "Filter by direction: incoming, outgoing, or both (default: both)" }
  },
  "required": ["coin"]
}
```

**Behavior:**
1. Validate coin is enabled
2. Based on `direction` param:
   - `"incoming"` → call `walletSupervisor.execute(wallet -> wallet.pendingIncoming())`
   - `"outgoing"` → call `walletSupervisor.execute(wallet -> wallet.pendingOutgoing())`
   - `"both"` / absent → call both (two sequential `execute()` calls) and merge
3. Return transaction list as JSON

**Response:**
```json
{
  "coin": "bitcoin",
  "direction": "both",
  "transactions": [
    {
      "txId": "abc123...",
      "direction": "INCOMING",
      "address": "bc1q...",
      "amount": "0.05000000",
      "fee": "0.00001234",
      "txKey": null,
      "confirmations": 0,
      "confirmed": false,
      "timestamp": "2026-03-03T12:00:00Z",
      "extras": {}
    }
  ],
  "count": 1
}
```

**File:** `io.konkin.agent.mcp.driver.PendingTransactionsTool`

---

### Tool 5: `sign_message` (write operation — no approval needed)

**Library method:** `CoinWallet.signMessage(String message)` → `SignedMessage(Coin coin, String address, String message, String signature)`

**Description:** Sign a message with a wallet address to prove ownership.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "coin": { "type": "string", "description": "Coin identifier: bitcoin" },
    "message": { "type": "string", "description": "The message to sign" }
  },
  "required": ["coin", "message"]
}
```

**Behavior:**
1. Validate coin is enabled
2. Call `walletSupervisor.execute(wallet -> wallet.signMessage(message))`
3. Return signed message record as JSON

**Response:**
```json
{
  "coin": "BTC",
  "address": "bc1q...",
  "message": "Hello, world!",
  "signature": "H+..."
}
```

**File:** `io.konkin.agent.mcp.driver.SignMessageTool`

---

### Tool 6: `verify_message` (read-only)

**Library method:** `CoinWallet.verifyMessage(String message, String address, String signature)` → `boolean`

**Description:** Verify a signed message against an address and signature.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "coin": { "type": "string", "description": "Coin identifier: bitcoin" },
    "message": { "type": "string", "description": "The original message that was signed" },
    "address": { "type": "string", "description": "The address that allegedly signed the message" },
    "signature": { "type": "string", "description": "The signature to verify" }
  },
  "required": ["coin", "message", "address", "signature"]
}
```

**Behavior:**
1. Validate coin is enabled
2. Call `walletSupervisor.execute(wallet -> wallet.verifyMessage(message, address, signature))`
3. Return verification result

**Response:**
```json
{ "coin": "bitcoin", "address": "bc1q...", "valid": true }
```

**File:** `io.konkin.agent.mcp.driver.VerifyMessageTool`

---

### Tool 7: `send_coin` (existing — approval flow)

**Library method:** `CoinWallet.send(SendRequest)` → `SendResult(Coin coin, String txId, BigDecimal amount, BigDecimal fee, Map<String, String> extras)`

**Description:** Already implemented in `SendCoinTool.java`. Creates an approval request (QUEUED → PENDING → APPROVED). Actual execution happens in `TransactionExecutionService` which calls `walletSupervisor.execute(wallet -> wallet.send(request))`.

**No changes needed** — listed here for completeness.

---

### MCP Tool Registration Changes

**`McpAgentServer.registerDriverPrimitives()`** must register all new tools. The `WalletSupervisor` reference needs to be passed into `McpAgentServer` so tools can call `execute()`.

**Constructor change:** Add `WalletSupervisor walletSupervisor` parameter (nullable — null when wallet is disabled).

**Updated registration:**
```java
private void registerDriverPrimitives() {
    // existing resources
    if (primaryConfigRequirementsService != null) {
        mcpSyncServer.addResource(ConfigRequirementsResource.serverResource(primaryConfigRequirementsService));
        mcpSyncServer.addResourceTemplate(ConfigRequirementsResource.coinTemplate(primaryConfigRequirementsService));
    }

    // existing send_coin tool (approval flow — does not need supervisor)
    if (requestRepo != null && historyRepo != null && runtimeConfig != null) {
        mcpSyncServer.addTool(SendCoinTool.create(agentName, requestRepo, historyRepo, runtimeConfig));
    }

    // NEW: wallet query tools (direct execution via supervisor)
    if (walletSupervisor != null && runtimeConfig != null) {
        mcpSyncServer.addTool(WalletStatusTool.create(walletSupervisor, runtimeConfig));
        mcpSyncServer.addTool(WalletBalanceTool.create(walletSupervisor, runtimeConfig));
        mcpSyncServer.addTool(DepositAddressTool.create(walletSupervisor, runtimeConfig));
        mcpSyncServer.addTool(PendingTransactionsTool.create(walletSupervisor, runtimeConfig));
        mcpSyncServer.addTool(SignMessageTool.create(walletSupervisor, runtimeConfig));
        mcpSyncServer.addTool(VerifyMessageTool.create(walletSupervisor, runtimeConfig));
    }

    // existing decision status + poller
    if (requestRepo != null && depLoader != null) {
        mcpSyncServer.addResourceTemplate(DecisionStatusResource.template(requestRepo, depLoader));
        decisionNotificationPoller = new DecisionNotificationPoller(requestRepo, depLoader, mcpSyncServer);
        decisionNotificationPoller.start();
    }
    mcpSyncServer.addPrompt(DriverReadinessPrompt.create());
}
```

---

### Common Tool Helper: `WalletToolSupport`

All 6 new tools share the same boilerplate for coin validation and error handling. Extract a utility:

**File:** `io.konkin.agent.mcp.driver.WalletToolSupport`

```java
package io.konkin.agent.mcp.driver;

final class WalletToolSupport {
    private WalletToolSupport() {}

    /** Validate coin param and return the matching config, or null. */
    static CoinConfig resolveCoin(KonkinConfig config, String coin) { ... }

    /** Standard error JSON result. */
    static CallToolResult errorResult(String error, String message) { ... }

    /** Wrap a WalletException into a CallToolResult. */
    static CallToolResult walletError(WalletException e) {
        if (e instanceof WalletConnectionException) {
            return errorResult("wallet_offline", e.getMessage());
        }
        if (e instanceof WalletInsufficientFundsException ise) {
            return errorResult("insufficient_funds",
                "Requested " + ise.requested() + " but only " + ise.available() + " available");
        }
        return errorResult("wallet_error", e.getMessage());
    }

    /** Serialize object to JSON CallToolResult. */
    static CallToolResult jsonResult(Object value) { ... }
}
```

---

### MCP Tool ↔ Library Function Mapping (Complete)

| MCP Tool | Library Method | Input | Output | Approval? |
|---|---|---|---|---|
| `wallet_status` | `CoinWallet.status()` | coin | `WalletStatus` | No |
| `wallet_balance` | `CoinWallet.balance()` | coin | `WalletBalance` | No |
| `deposit_address` | `CoinWallet.depositAddress()` | coin | `DepositAddress` | No |
| `pending_transactions` | `CoinWallet.pendingIncoming()` / `pendingOutgoing()` | coin, direction? | `List<Transaction>` | No |
| `sign_message` | `CoinWallet.signMessage(String)` | coin, message | `SignedMessage` | No |
| `verify_message` | `CoinWallet.verifyMessage(String, String, String)` | coin, message, address, signature | `boolean` | No |
| `send_coin` | `CoinWallet.send(SendRequest)` | coin, toAddress, amount, fee*, memo* | `SendResult` | **Yes** |

---

## Summary of New/Modified Files

| # | File | Action | Purpose |
|---|------|--------|---------|
| 1 | `io.konkin.crypto.WalletSnapshot` | **create** | Immutable thread-safe wallet state |
| 2 | `io.konkin.crypto.WalletSecretLoader` | **create** | Parse secret files → WalletConnectionConfig |
| 3 | `io.konkin.crypto.WalletSupervisor` | **create** | Background lifecycle: connect, heartbeat, reconnect, serialize RPC |
| 4 | `io.konkin.crypto.TransactionExecutionService` | **create** | Poll APPROVED requests, execute sends, record results |
| 5 | `io.konkin.web.KonkinWebServer` | **modify** | Create supervisor + execution service, pass to mapper/tools, shutdown |
| 6 | `io.konkin.web.LandingPageMapper` | **modify** | Show live status/balance/heartbeat, filter unconnected coins |
| 7 | `io.konkin.agent.mcp.driver.SendCoinTool` | **no change** | Execution is decoupled via TransactionExecutionService |
| 8 | `io.konkin.agent.mcp.driver.WalletToolSupport` | **create** | Shared coin validation, error handling, JSON serialization for wallet tools |
| 9 | `io.konkin.agent.mcp.driver.WalletStatusTool` | **create** | MCP tool: `wallet_status` → `CoinWallet.status()` |
| 10 | `io.konkin.agent.mcp.driver.WalletBalanceTool` | **create** | MCP tool: `wallet_balance` → `CoinWallet.balance()` |
| 11 | `io.konkin.agent.mcp.driver.DepositAddressTool` | **create** | MCP tool: `deposit_address` → `CoinWallet.depositAddress()` |
| 12 | `io.konkin.agent.mcp.driver.PendingTransactionsTool` | **create** | MCP tool: `pending_transactions` → `pendingIncoming()` / `pendingOutgoing()` |
| 13 | `io.konkin.agent.mcp.driver.SignMessageTool` | **create** | MCP tool: `sign_message` → `CoinWallet.signMessage()` |
| 14 | `io.konkin.agent.mcp.driver.VerifyMessageTool` | **create** | MCP tool: `verify_message` → `CoinWallet.verifyMessage()` |
| 15 | `io.konkin.agent.McpAgentServer` | **modify** | Accept `WalletSupervisor`, register all wallet tools in driver primitives |
