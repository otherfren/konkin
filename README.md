**Pre-alpha — no official release yet.**

# Konkin.io

MCP crypto wallet wrapper for AI agents.

- Send crypto via natural language while keeping secret keys hidden from your agents.
- Supports 2FA via Telegram and other agents.

## Quick Start

**Requires:** Java 21+, Maven 3.9+, at least one crypto wallet daemon.

```bash
mvn clean install
java -jar target/konkin-server-0.1.0-SNAPSHOT.jar config.toml
```

On first run, secrets are auto-generated under `./secrets/` and credentials are printed to stdout. **Copy them** — cleartext secrets are only shown once.

## Configure

Edit `config.toml` in the working directory. Defaults work out of the box (H2 database, no external services).

| Section                | Default              | Purpose                          |
|------------------------|----------------------|----------------------------------|
| `[server]`             | `127.0.0.1:7070`     | Web UI + REST API                |
| `[agents.primary]`     | `127.0.0.1:9550`     | Driver MCP server (your AI)      |
| `[agents.secondary.*]` | `9560`, `9561`       | Auth MCP servers (approval flow) |
| `[coins.*]`            | btc, ltc, xmr        | Enabled coins and auth rules     |

## Connect Claude Agent

1. Read credentials from `./secrets/agent-primary.secret`

2. Get a bearer token:
   ```bash
   curl -s -X POST "http://127.0.0.1:9550/oauth/token" \
     -d "grant_type=client_credentials&client_id=konkin-primary&client_secret=YOUR_SECRET"
   ```
   Tokens don't expire. Max 2 active per agent; a 3rd evicts the oldest.

3. Register with Claude Code:
   ```bash
   claude mcp add --transport sse \
     -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
     -s project konkin "http://127.0.0.1:9550/sse"
   ```

For other agents, refer to their MCP documentation.

## Verify

- Web UI: `http://127.0.0.1:7070`
- Health: `http://127.0.0.1:7070/api/v1/health`
- MCP SSE: `http://127.0.0.1:9550/sse`

## Troubleshooting

| Problem                         | Fix                                                                          |
|---------------------------------|------------------------------------------------------------------------------|
| `401 Unauthorized`              | Re-issue token via `/oauth/token` and update settings.                       |
| `NOT_READY`                     | Check `config.toml` and `./secrets/` for missing coin config or secrets.     |
| MCP not showing in Claude       | Verify `.mcp.json` is valid. Restart Claude Code.                            |
| `429 rate_limited`              | Wait 60s, retry with correct credentials.                                    |
| Agent using curl instead of MCP | See `documents/SKILL-driver-agent.md` — agents must use MCP primitives only. |
