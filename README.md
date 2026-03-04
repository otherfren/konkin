# Konkin.io

Konkin.io is an MCP crypto wallet wrapper for AI agents.

- You can send crypto using natural language.
- It keeps secret keys completely hidden from your AI agents.
- It includes 2FA via Telegram and other agents.

**Konkin.io currently in early alpha and supports only Bitcoin right now.**

I’ll be adding more coins soon.


---

## Prerequisites

- **Java 21** or newer
- **Maven 3.9** or newer
- at least one crypto wallet daemon

---

## Build

```bash
mvn clean install
```

This produces `target/konkin-server-0.1.0-SNAPSHOT.jar`.

---

## Configure

Copy or edit `config.toml` in the working directory. The defaults work out of the box for local development (H2 database, no external services required).

| Section                | Default                   | Purpose                                         |
|------------------------|---------------------------|-------------------------------------------------|
| `[server]`             | `127.0.0.1:7070`          | Web UI + REST API                               |
| `[agents.primary]`     | `127.0.0.1:9550`          | Driver MCP server (the one your AI connects to) |
| `[agents.secondary.*]` | `9560`, `9561`            | Auth MCP servers (for optional approval agents) |
| `[coins.*]`            | bitcoin, litecoin, monero | Enabled coins and auth rules                    |

Secret files are **auto-generated on first startup** under `./secrets/`.

---

## Run

```bash
java -jar target/konkin-server-0.1.0-SNAPSHOT.jar config.toml
```

On first run, Konkin creates `./secrets/` and prints credentials to stdout. **Copy them** — cleartext secrets are only shown once.

---

## Verify

| What             | URL                                   |
|------------------|---------------------------------------|
| Web UI           | `http://127.0.0.1:7070`               |
| REST health      | `http://127.0.0.1:7070/api/v1/health` |
| MCP SSE endpoint | `http://127.0.0.1:9550/sse`           |

---

## Connect Claude / Sonnet to Konkin

### 1. Find your credentials

Open `./secrets/agent-primary.secret` (auto-created on first boot):

```properties
client-id=konkin-primary
client-secret=<your-generated-secret>
```

### 2. Get a bearer token

```bash
curl -s -X POST "http://127.0.0.1:9550/oauth/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=konkin-primary" \
  -d "client_secret=YOUR_SECRET"
```

Copy the `access_token` from the JSON response. Tokens do not expire and survive server restarts (persisted in H2). Max 2 active tokens per agent; issuing a 3rd evicts the oldest.

### 3. Register the MCP server with Claude Code

From your project directory, run:

```bash
claude mcp add --transport sse \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -s project \
  konkin "http://127.0.0.1:9550/sse"
```

This creates a `.mcp.json` in the project root. Restart Claude Code — you should see `konkin` in the MCP server list. Verify with `claude mcp list`.

### 4. Tell your agent how to use Konkin

Copy the contents of **`documents/SKILL-driver-agent.md`** into your agent's instructions (e.g. paste it into Claude Code's custom instructions, or into your project's `.claude/` directory as a skill file).

That file contains everything the agent needs: what MCP primitives to call, in what order, and what NOT to do (like generating curl commands instead of using the MCP connection).

---

## Troubleshooting

| Problem                                 | Fix                                                                                                                                   |
|-----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `401 Unauthorized`                      | Token invalid. Re-run the `/oauth/token` curl and update your settings. Tokens survive restarts but a 3rd issuance evicts the oldest. |
| `NOT_READY` from readiness check        | Coins not enabled or secret files missing. Check `config.toml` and `./secrets/`.                                                      |
| MCP server not showing in Claude        | Verify `~/.claude/settings.json` is valid JSON. Restart Claude Code.                                                                  |
| `429 rate_limited`                      | Too many failed auth attempts. Wait 60s, retry with correct credentials.                                                              |
| Agent using curl/bash to talk to Konkin | Wrong. See `documents/SKILL-driver-agent.md` — the agent must use MCP primitives only.                                                |
