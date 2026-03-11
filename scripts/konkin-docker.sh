#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  konkin-docker.sh — run konkin.io in Docker (web-ui only)
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

IMAGE_NAME="konkin-server"
CONTAINER_NAME="konkin"
DATA_DIR="$(pwd)/konkin-data"

# ── colors ─────────────────────────────────────────────────────
BOLD='\033[1m'
GREEN='\033[32m'
YELLOW='\033[33m'
CYAN='\033[36m'
RED='\033[31m'
RESET='\033[0m'

info()  { echo -e "${CYAN}[info]${RESET}  $*"; }
ok()    { echo -e "${GREEN}[ok]${RESET}    $*"; }
warn()  { echo -e "${YELLOW}[warn]${RESET}  $*"; }
err()   { echo -e "${RED}[error]${RESET} $*"; }

banner() {
    echo ""
    echo -e "${BOLD}╔═══════════════════════════════════════╗${RESET}"
    echo -e "${BOLD}║         konkin.io  docker setup        ║${RESET}"
    echo -e "${BOLD}╚═══════════════════════════════════════╝${RESET}"
    echo ""
}

# ── pre-flight checks ─────────────────────────────────────────
preflight() {
    if ! command -v docker &>/dev/null; then
        err "Docker is not installed. Please install Docker first:"
        echo "    https://docs.docker.com/get-docker/"
        exit 1
    fi
    if ! docker info &>/dev/null 2>&1; then
        err "Docker daemon is not running (or you lack permissions)."
        echo "    Try: sudo systemctl start docker"
        echo "    Or add yourself to the docker group: sudo usermod -aG docker \$USER"
        exit 1
    fi
    ok "Docker is available"
}

# ── find repo root (look for pom.xml) ─────────────────────────
find_repo() {
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

    if [ ! -f "$REPO_ROOT/pom.xml" ] || [ ! -f "$REPO_ROOT/Dockerfile" ]; then
        err "Cannot find konkin repository. Run this script from the project, e.g.:"
        echo "    ./scripts/konkin-docker.sh"
        exit 1
    fi
    ok "Repository found at $REPO_ROOT"
}

# ── ask for data directory ─────────────────────────────────────
ask_data_dir() {
    echo ""
    echo -e "Where should konkin store its data (database, secrets, logs)?"
    read -rp "  Data directory [$DATA_DIR]: " INPUT_DIR
    DATA_DIR="${INPUT_DIR:-$DATA_DIR}"
    DATA_DIR="$(realpath -m "$DATA_DIR")"

    if [ -d "$DATA_DIR" ]; then
        info "Using existing directory: $DATA_DIR"
    else
        info "Will create: $DATA_DIR"
    fi
}

# ── generate docker config.toml ───────────────────────────────
generate_config() {
    mkdir -p "$DATA_DIR"

    CONFIG_FILE="$DATA_DIR/config.toml"

    if [ -f "$CONFIG_FILE" ]; then
        warn "Config already exists at $CONFIG_FILE — keeping it."
        return
    fi

    DOCKER_CONFIG="$REPO_ROOT/docker/config.toml"
    if [ ! -f "$DOCKER_CONFIG" ]; then
        err "Docker config template not found at $DOCKER_CONFIG"
        exit 1
    fi

    cp "$DOCKER_CONFIG" "$CONFIG_FILE"
    ok "Generated config at $CONFIG_FILE"
}

# ── create telegram secret if missing ─────────────────────────
setup_telegram_secret() {
    SECRETS_DIR="$DATA_DIR/secrets"
    mkdir -p "$SECRETS_DIR"

    TELEGRAM_SECRET="$SECRETS_DIR/telegram.secret"
    if [ -f "$TELEGRAM_SECRET" ]; then
        ok "Telegram secret already exists — keeping it."
        return
    fi

    echo ""
    echo -e "${BOLD}Telegram bot setup${RESET}"
    echo ""
    echo "  Konkin uses a Telegram bot for authorization notifications."
    echo "  You need a bot token from @BotFounder and your chat ID(s)."
    echo ""
    read -rp "  Bot token (or press Enter to skip for now): " BOT_TOKEN

    if [ -z "$BOT_TOKEN" ]; then
        export BOT_TOKEN="REPLACE_ME"
        export CHAT_IDS="REPLACE_ME"
        envsubst < "$REPO_ROOT/docker/telegram.secret.template" > "$TELEGRAM_SECRET"
        warn "Telegram secret created with placeholders at $TELEGRAM_SECRET"
        warn "Konkin will NOT start HTTP services until you fill in the real values."
        echo "  Edit:  $TELEGRAM_SECRET"
    else
        read -rp "  Chat ID(s) (comma-separated): " CHAT_IDS
        export BOT_TOKEN
        export CHAT_IDS="${CHAT_IDS:-REPLACE_ME}"
        envsubst < "$REPO_ROOT/docker/telegram.secret.template" > "$TELEGRAM_SECRET"
        ok "Telegram secret saved at $TELEGRAM_SECRET"
    fi
}

# ── create bitcoin daemon secret if missing ───────────────────
setup_bitcoin_secret() {
    SECRETS_DIR="$DATA_DIR/secrets"
    mkdir -p "$SECRETS_DIR"

    BTC_DAEMON_SECRET="$SECRETS_DIR/bitcoin-daemon.conf"
    if [ -f "$BTC_DAEMON_SECRET" ]; then
        ok "Bitcoin daemon secret already exists — keeping it."
        return
    fi

    echo ""
    echo -e "${BOLD}Bitcoin node RPC credentials${RESET}"
    echo ""
    read -rp "  RPC user [bitcoin]: " BTC_RPC_USER
    export BTC_RPC_USER="${BTC_RPC_USER:-bitcoin}"
    read -rp "  RPC password [bitcoin]: " BTC_RPC_PASSWORD
    export BTC_RPC_PASSWORD="${BTC_RPC_PASSWORD:-bitcoin}"
    read -rp "  RPC port [8332]: " BTC_RPC_PORT
    export BTC_RPC_PORT="${BTC_RPC_PORT:-8332}"

    envsubst < "$REPO_ROOT/docker/bitcoin-daemon.conf.template" > "$BTC_DAEMON_SECRET"
    ok "Bitcoin daemon secret saved at $BTC_DAEMON_SECRET"
}

# ── build image ────────────────────────────────────────────────
build_image() {
    echo ""
    info "Building Docker image (this may take a few minutes on the first run)..."
    echo ""

    docker build -t "$IMAGE_NAME" "$REPO_ROOT"

    echo ""
    ok "Image built: $IMAGE_NAME"
}

# ── stop old container if running ──────────────────────────────
stop_old() {
    if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
        warn "Removing existing container '$CONTAINER_NAME'..."
        docker rm -f "$CONTAINER_NAME" >/dev/null
    fi
}

# ── run container ──────────────────────────────────────────────
run_container() {
    echo ""
    info "Starting konkin..."

    docker run -d \
        --name "$CONTAINER_NAME" \
        --network host \
        -v "$DATA_DIR/data:/app/data" \
        -v "$DATA_DIR/secrets:/app/secrets" \
        -v "$DATA_DIR/logs:/app/logs" \
        -v "$DATA_DIR/config.toml:/app/config.toml:ro" \
        --restart unless-stopped \
        "$IMAGE_NAME"

    ok "Container '$CONTAINER_NAME' is running"
}

# ── wait for startup and show password ─────────────────────────
show_result() {
    echo ""
    info "Waiting for startup..."

    for i in $(seq 1 30); do
        if docker logs "$CONTAINER_NAME" 2>&1 | grep -qi "started\|listening\|javalin"; then
            break
        fi
        sleep 1
    done

    echo ""
    echo -e "${BOLD}═══════════════════════════════════════════${RESET}"
    echo ""
    echo -e "  ${GREEN}konkin.io is running!${RESET}"
    echo ""
    echo -e "  Web-UI:   ${BOLD}http://localhost:7070${RESET}"
    echo ""

    PASSWORD_FILE="$DATA_DIR/secrets/web-ui.password"
    if [ -f "$PASSWORD_FILE" ]; then
        PASSWORD=$(cat "$PASSWORD_FILE")
        echo -e "  Password: ${BOLD}${PASSWORD}${RESET}"
    else
        echo -e "  ${YELLOW}Password will be auto-generated on first access.${RESET}"
        echo -e "  Check:    ${BOLD}cat $PASSWORD_FILE${RESET}"
    fi

    echo ""
    echo -e "${BOLD}═══════════════════════════════════════════${RESET}"
    echo ""
    echo "  Useful commands:"
    echo "    docker logs -f $CONTAINER_NAME     — follow logs"
    echo "    docker stop $CONTAINER_NAME        — stop"
    echo "    docker start $CONTAINER_NAME       — start again"
    echo "    docker rm -f $CONTAINER_NAME       — remove"
    echo ""
    echo "  Data stored in: $DATA_DIR"
    echo ""
}

# ── main ───────────────────────────────────────────────────────
banner
preflight
find_repo
ask_data_dir
generate_config
setup_telegram_secret
setup_bitcoin_secret
build_image
stop_old
run_container
show_result
