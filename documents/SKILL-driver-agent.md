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

### 

### Planned next routes (for continuing implementation)

- `POST /coins/{coin}/actions/send`
- `GET /decisions/{requestId}`
- `GET /decisions/{requestId}/events`

## Mandatory execution protocol

1. Authenticate first.
2. Call readiness endpoint.
3. If `status=NOT_READY`, stop and report blockers from `missing` and `invalid`.
4. If `status=READY`, proceed to action endpoint when available.
5. Track status only from decision APIs/events once available.

## Readiness interpretation rules

- `READY`: no blocking issues in `missing` or `invalid`.
- `NOT_READY`: one or more blocking entries exist.
- Treat payload from service as source of truth.

## Error handling

Classify errors as one of:

- `auth` (401/403)
- `config` (readiness NOT_READY)
- `network` (timeouts/connectivity)
- `server` (5xx)

Always return:

- HTTP status code
- endpoint called
- deterministic next step

## Required response shape

Return responses in this machine-usable structure:

```json
{
  "mode": "readiness|action-submit|decision-track",
  "coin": "bitcoin",
  "readiness": "READY|NOT_READY",
  "blockers": [
    {
      "key": "string",
      "status": "missing|invalid",
      "message": "string",
      "hint": "string",
      "blocking": true
    }
  ],
  "next_call": {
    "method": "GET|POST",
    "path": "string",
    "reason": "string"
  },
  "confidence": "high|medium|low"
}
```

## Hard constraints

- Never invent `requestId`.
- Never invent decision transitions.
- Never claim submission unless accepted response is returned.
- Keep output concise and operational.

## Current implementation anchors

- Primary readiness route: `GET /runtime/config/requirements`
- Auth gate applies to protected routes.
- Readiness payload includes `checks`, `missing`, `invalid` with blocking metadata.

## Operator-oriented output guideline

When NOT_READY, print exactly:

1. Blocking keys
2. Why each is blocking
3. Fix hints
4. The exact next API call to retry readiness

When READY, print exactly:

1. Confirmation that readiness passed
2. Next action endpoint to call
3. Required request fields for that action
