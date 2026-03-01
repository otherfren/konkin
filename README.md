# Konkin

Konkin is a self-hosted server that brokers blockchain send operations through human-in-the-loop approval workflows.
An AI driver agent submits send requests via MCP; one or more auth agents (web-ui, Telegram, REST-Api or other AI) approve or deny them.



## Quickstart

**Prerequisites:** Java 21+, Maven 3.9+

### 1. Build

```bash
mvn clean install
```

This produces `target/konkin-server-0.1.0-SNAPSHOT.jar`.

### 2. Configure

Copy or edit `config.toml` in the working directory. The defaults work out of the box for local development (H2 database, no Telegram).

Key sections:

| Section                | Default                   | Purpose                                         |
|------------------------|---------------------------|-------------------------------------------------|
| `[server]`             | `127.0.0.1:7070`          | Web UI + REST API                               |
| `[agents.primary]`     | `127.0.0.1:9550`          | Driver MCP server (for AI agent)                |
| `[agents.secondary.*]` | `9560`, `9561`            | Auth MCP servers (for optional approval agents) |
| `[coins.*]`            | bitcoin, litecoin, monero | Enabled coins and auth rules                    |

Secret files (API keys, agent tokens) are auto-generated on first startup under `./secrets/`.

### 3. Run

```bash
java -jar target/konkin-server-0.1.0-SNAPSHOT.jar
```

Or pass a custom config path:

```bash
java -jar target/konkin-server-0.1.0-SNAPSHOT.jar /path/to/config.toml
```

### 4. Verify

- Web UI: http://127.0.0.1:7070
- Health: http://127.0.0.1:7070/api/v1/health
- Driver MCP health: `konkin://health` via MCP at `http://127.0.0.1:9550/sse`

### 5. Connect an AI driver agent

The Konkin MCP server uses OAuth2 client credentials.
The client secret is auto-generated at `./secrets/agent-*name*.secret` on first startup.

Add to your MCP client config (e.g. Claude Code `~/.claude/settings.json`):

```json
{
  "mcpServers": {
    "konkin-driver": {
      "type": "sse",
      "url": "http://127.0.0.1:9550/sse",
      "headers":
      {
        "Authorization": "Bearer <from secrets/agent-primary.secret>"
      }
    }
  }
}
```

Alternative authentication:

```
POST http://127.0.0.1:9550/oauth/token
grant_type=client_credentials&client_id=konkin-primary&client_secret=<from secrets/agent-primary.secret>
```

Use the returned Bearer token in the `Authorization` header for MCP requests.

See `documents/SKILL-driver-agent.md` for the full driver agent operating guide.


## Programmed by Peter Geschel https://x.com/otherfren

*Keeper of Nodes, Keys, and Independent Networks*

