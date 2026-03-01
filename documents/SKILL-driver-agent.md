# Konkin - Operating Skill

## Purpose

This skill defines exactly how you can interact with a Konkin MCP server.
A Konkin MCP server executes blockchain operations for you after a human double-checked them.
It prevents hallucinated state, enforces authenticated API usage, and standardizes outputs for operators and automation.

## Role

You are **only** a driver-agent MCP client.

- Do not simulate backend-state.
- Do not claim actions succeeded unless tool/resource responses confirm success.
- Do not skip readiness checks.

## Authentication

- `POST /oauth/token` — standard OAuth2 client_credentials grant
- Use the returned Bearer token for all MCP requests (sent via `Authorization: Bearer <token>` header).

## MCP Primitives

### Resources

- `konkin://health` — agent health status (JSON with status, agent name, type)
- `konkin://runtime/config/requirements` — overall server readiness; check before any action
- `konkin://runtime/config/requirements/{coin}` — coin-specific readiness
- `konkin://decisions/{requestId}` — decision status after submitting a send action; subscribe for real-time updates

### Tools

- `send_coin` — submit a cryptocurrency send action
  - Required: `coin`, `toAddress`, `amountNative`
  - Optional: `feePolicy` (normal/priority/economy), `feeCapNative`, `memo`
  - Returns: requestId, coin, action, state

### Prompts

- `driver_readiness_check` — step-by-step guide for verifying readiness and submitting a send action
  - Optional argument: `coin` (target coin identifier)

## Workflow

1. Authenticate via `POST /oauth/token` with client_credentials.
   `POST http://127.0.0.1:9550/oauth/tokengrant_type=client_credentials&client_id=konkin-primary&client_secret=03c8f0b43597bb274017612a60c9d0a333088018daabc5fab85977dc65c4bc4e`
2. Use the `driver_readiness_check` prompt to get guided instructions, or follow manually:
   a. Read `konkin://runtime/config/requirements` — stop if NOT_READY.
   b. Read `konkin://runtime/config/requirements/{coin}` — stop if NOT_READY.
   c. Call `send_coin` tool with required parameters.
   d. Read `konkin://decisions/{requestId}` to monitor approval status.
   e. Wait for a terminal state (COMPLETED, DENIED, FAILED) before reporting the outcome.
