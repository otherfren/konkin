#!/bin/sh
# Fix ownership of mounted volumes (created as root by Docker)
chown -R konkin:konkin /app/data /app/secrets /app/logs 2>/dev/null || true

exec su-exec konkin java -jar konkin-server.jar config.toml
