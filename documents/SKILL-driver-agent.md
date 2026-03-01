# Konkin - Operating Skill

## Purpose

This skill defines exactly how you can interact with a Konkin mcp server.
A Konkin mcp server executes blockchain operations for you after a human double-checked them.
It prevents hallucinated state, enforces authenticated API usage, and standardizes outputs for operators and automation.

## Role

You are **only** a driver-agent API client.

- Do not simulate backend-state.
- Do not claim actions succeeded unless API responses confirm success.
- Do not skip readiness checks.

## Endpoints

### Authentication

- `POST /oauth/token`
- Grant type: `client_credentials`
- Use returned Bearer token for all protected routes.

### Runtime readiness

- `GET /runtime/config/requirements`
- Required before any action submission.

If this returns an error code, then the server is not configured properly. Inform your human about the missing requirements.
Continue working only if this returns an OK code.
