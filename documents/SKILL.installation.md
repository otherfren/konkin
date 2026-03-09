# Installing and Running a Konkin Server

You are helping a human install and run a Konkin server from scratch.
Follow every section in order. Do not skip steps. Do not assume anything is already done.

**Your job is to walk the human through each step. You do NOT run these commands yourself.**

---

## Step 1: Download the Latest Release

The Konkin server is distributed as a single `.jar` file from GitHub Releases.

Tell the human to open a terminal and run:

```bash
curl -sL "https://api.github.com/repos/otherfren/konkin/releases/latest" \
  | grep -oP '"browser_download_url": "\K[^"]+\.jar"' \
  | tr -d '"' \
  | head -1 \
  | xargs -I {} curl -Lo konkin-server.jar "{}"
```

Also download the PGP signature file (`.jar.asc`):

```bash
curl -sL "https://api.github.com/repos/otherfren/konkin/releases/latest" \
  | grep -oP '"browser_download_url": "\K[^"]+\.jar\.asc"' \
  | tr -d '"' \
  | head -1 \
  | xargs -I {} curl -Lo konkin-server.jar.asc "{}"
```

After running both commands, the human should have two files in their current directory:
- `konkin-server.jar` — the server
- `konkin-server.jar.asc` — the PGP signature

If the human prefers a browser: tell them to go to `https://github.com/otherfren/konkin/releases/latest` and download the `.jar` and `.jar.asc` files manually.

---

## Step 2: Verify the Download with PGP

This step confirms the `.jar` file was actually published by the Konkin maintainer and has not been tampered with.

### Step 2a: Install GnuPG (if not already installed)

- **Ubuntu/Debian:** `sudo apt install gnupg`
- **Fedora/RHEL:** `sudo dnf install gnupg2`
- **macOS (Homebrew):** `brew install gnupg`
- **Arch:** `sudo pacman -S gnupg`

The human can check if GnuPG is already installed by running: `gpg --version`

### Step 2b: Import the Konkin signing key

The signing key fingerprint is: `E155 7E46 9333 3D58 CDFC ADB4 F23B 992B B897 C13D`

The human must import the public key. They can do this from a keyserver:

```bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys E1557E4693333D58CDFCADB4F23B992BB897C13D
```

Alternatively, if they have the `pgp.txt` file (included in the repository under `documents/pgp.txt`), they can import it directly:

```bash
gpg --import pgp.txt
```

### Step 2c: Verify the signature

The human runs:

```bash
gpg --verify konkin-server.jar.asc konkin-server.jar
```

**What the human should see:**

A line that says `Good signature from "Dipl.-Inf. Peter Geschel <fren@kek.to>"`.

There may also be a warning about the key not being certified with a trusted signature. That is normal and expected unless the human has personally signed the key. The important part is that the signature is marked `Good`.

**What means something is wrong:**

If the output says `BAD signature`, the file has been tampered with. Tell the human to delete both files and re-download from the official GitHub release page.

---

## Step 3: Install Java

Konkin requires Java 21 or newer. Nothing older will work.

### Step 3a: Check if Java is already installed

```bash
java -version
```

If the output shows a version number of 21 or higher (for example `openjdk version "21.0.2"`), skip to Step 4.

If the command is not found or the version is below 21, continue with Step 3b.

### Step 3b: Install Java 21

Tell the human to pick ONE of the following methods based on their operating system.

**Ubuntu/Debian:**

```bash
sudo apt update
sudo apt install openjdk-21-jre-headless
```

**Fedora/RHEL:**

```bash
sudo dnf install java-21-openjdk-headless
```

**Arch Linux:**

```bash
sudo pacman -S jre21-openjdk-headless
```

**macOS (Homebrew):**

```bash
brew install openjdk@21
```

After installing on macOS with Homebrew, the human also needs to symlink it:

```bash
sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

**Windows:**

Download the installer from `https://adoptium.net/temurin/releases/?version=21` — pick the `.msi` installer for Windows x64. Run the installer. Make sure the option to set JAVA_HOME is checked.

### Step 3c: Confirm the installation

```bash
java -version
```

The output must show version 21 or higher. If it does not, Java was not installed correctly. Go back to Step 3b.

---

## Step 4: Create a Working Directory

The human needs a dedicated directory for Konkin. All data, logs, secrets, and config will live here.

```bash
mkdir -p ~/konkin
cp konkin-server.jar ~/konkin/
cd ~/konkin
```

---

## Step 5: Create the Configuration File

The human must create a file called `config.toml` inside `~/konkin/`. This is the main configuration file.

**First, ask the human which coins they want to enable:**
- Bitcoin only
- Monero only
- Both Bitcoin and Monero

Based on their answer, tell the human to create `~/konkin/config.toml` with the appropriate content below.

### Base configuration (always include this part)

```toml
config-version = 1

[server]
host = "127.0.0.1"
port = 7070
secrets-dir = "./secrets"
log-level = "info"
log-file = "./logs/konkin.log"
log-rotate-max-size-mb = 10

[database]
url = "jdbc:h2:./data/konkin;AUTO_SERVER=TRUE"
user = "sa"
password = "sa"
pool-size = 5

[web-ui]
enabled = true

[web-ui.password-protection]
enabled = true
password-file = "./secrets/web-ui.password"

[rest-api]
enabled = true
secret-file = "./secrets/rest-api.secret"

[agents.primary]
enabled = true
bind = "127.0.0.1"
port = 9550
secret-file = "./secrets/agent-primary.secret"

[agents.secondary.agent-arthur]
enabled = true
bind = "127.0.0.1"
port = 9560
secret-file = "./secrets/agent-arthur.secret"

[telegram]
enabled = true
secret-file = "./secrets/telegram.secret"
api-base-url = "https://api.telegram.org"
auto-deny-timeout = "3m"
```

### If the human wants Bitcoin, also add:

```toml
[coins.bitcoin]
enabled = true

[coins.bitcoin.secret-files]
bitcoin-daemon-config-file = "./secrets/bitcoin-daemon.conf"
bitcoin-wallet-config-file = "./secrets/bitcoin-wallet.conf"

[coins.bitcoin.auth]
web-ui = true
rest-api = true
telegram = true
mcp-auth-channels = ["agent-arthur"]
min-approvals-required = 1
```

### If the human wants Monero, also add:

```toml
[coins.monero]
enabled = true

[coins.monero.secret-files]
monero-daemon-config-file = "./secrets/monero-daemon.conf"
monero-wallet-rpc-config-file = "./secrets/monero-wallet-rpc.conf"

[coins.monero.auth]
web-ui = true
rest-api = true
telegram = true
mcp-auth-channels = ["agent-arthur"]
min-approvals-required = 1
```

### If the human wants both, add both coin sections to the same config file.

Explain to the human:
- The database password `sa` is automatically replaced with a random password on first startup. They do not need to change it.
- All secret files under `./secrets/` are auto-generated on first startup. They do not need to create them manually.
- Based on their coin choice, proceed to Step 8 (Bitcoin), Step 8M (Monero), or both.

---

## Step 6: Start the Server (First Run)

```bash
cd ~/konkin
java -jar konkin-server.jar config.toml
```

**What happens on first startup:**

1. The server creates the `./secrets/` directory.
2. It generates secret files with random credentials.
3. It prints agent credentials (client IDs and client secrets) to the terminal in big banners.
4. It creates the `./data/` directory for the H2 database.
5. It creates the `./logs/` directory for log files.

**IMPORTANT: Tell the human to copy the credentials printed to the terminal.** The cleartext secrets are only shown once during first startup. They can be rotated later in the web UI, but the human should save them now.

The server is ready when the terminal shows the Javalin startup message. The human can verify by opening a browser and going to: `http://127.0.0.1:7070`

They will be asked for a password. The password was auto-generated and saved to `./secrets/web-ui.password`. Tell the human to read it:

```bash
cat ./secrets/web-ui.password
```

---

## Step 7: Connect Telegram

Telegram is used for 2FA approval of crypto transactions. When the AI agent tries to send crypto, Konkin sends an approve/deny prompt to the human's Telegram chat.

### Step 7a: Create a Telegram bot

1. Open Telegram on the human's phone or desktop.
2. Search for `@BotFather` (the official Telegram bot for creating bots).
3. Send `/newbot` to @BotFather.
4. @BotFather will ask for a name. Type any name (for example: `My Konkin Bot`).
5. @BotFather will ask for a username. Type a username that ends in `bot` (for example: `my_konkin_bot`).
6. @BotFather will reply with a bot token. It looks like this: `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`. Copy this token.

### Step 7b: Put the bot token in the secret file

Stop the server (press Ctrl+C in the terminal where it is running).

Open the file `./secrets/telegram.secret` in a text editor. It looks like this:

```
# KONKIN Telegram secret file
bot-token=REPLACE_WITH_TELEGRAM_BOT_TOKEN
chat-ids=REPLACE_WITH_TELEGRAM_CHAT_IDS
```

Replace `REPLACE_WITH_TELEGRAM_BOT_TOKEN` with the token from @BotFather. For example:

```
bot-token=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
```

Do NOT change the `chat-ids` line yet. Save and close the file.

### Step 7c: Start the server again and discover chats

Start the server again:

```bash
cd ~/konkin
java -jar konkin-server.jar config.toml
```

Now tell the human to open Telegram and send any message (for example `/start`) to their bot. This registers the human's chat with the bot.

### Step 7d: Approve the chat in the web UI

1. Open `http://127.0.0.1:7070` in a browser.
2. Log in with the web UI password (from `./secrets/web-ui.password`).
3. Click on the **Telegram** section (or go to `http://127.0.0.1:7070/telegram`).
4. The web UI will show discovered chat requests — these are the chats that sent messages to the bot.
5. Click **Approve** next to the human's chat.

After approving, the chat ID is saved to `./secrets/telegram.secret` automatically. The human does not need to edit the file again.

### Step 7e: Test the Telegram connection

The Telegram section in the web UI shows the connection status. If it says "Connected" and the approved chat is listed, Telegram is ready.

From now on, when the AI agent requests to send crypto, Konkin will send an approve/deny prompt with inline buttons to the human's Telegram chat. The human taps Approve or Deny directly in Telegram.

---

## Step 8: Connect a Bitcoin Wallet

Skip this step if the human did not enable Bitcoin in `config.toml`.

Konkin does not run a Bitcoin node itself. It connects to an existing Bitcoin Core node via JSON-RPC. The human needs a running Bitcoin Core instance with RPC enabled.

### Step 8a: Install Bitcoin Core (if not already running)

If the human does not have Bitcoin Core installed, tell them to download it from `https://bitcoincore.org/en/download/` and follow the installation instructions for their OS.

After installing, the human must enable RPC in their Bitcoin Core configuration. The Bitcoin Core config file is usually at:

- **Linux:** `~/.bitcoin/bitcoin.conf`
- **macOS:** `~/Library/Application Support/Bitcoin/bitcoin.conf`
- **Windows:** `%APPDATA%\Bitcoin\bitcoin.conf`

The human must add (or verify) these lines in their `bitcoin.conf`:

```
server=1
rpcuser=YOUR_RPC_USERNAME
rpcpassword=YOUR_RPC_PASSWORD
rpcbind=127.0.0.1
rpcport=8332
```

The human picks their own `rpcuser` and `rpcpassword` values. They must remember them for the next step.

After editing `bitcoin.conf`, restart Bitcoin Core so the changes take effect.

### Step 8b: Create or load a wallet in Bitcoin Core

Bitcoin Core needs a loaded wallet. The human can check their existing wallets:

```bash
bitcoin-cli listwallets
```

If the list is empty, create a new wallet:

```bash
bitcoin-cli createwallet "mywallet"
```

Or load an existing one:

```bash
bitcoin-cli loadwallet "mywallet"
```

The human needs to remember the wallet name (for example `mywallet`).

### Step 8c: Configure Konkin to connect to Bitcoin Core

Stop the Konkin server (Ctrl+C).

Open `./secrets/bitcoin-daemon.conf`. It was auto-generated and looks like this:

```
# KONKIN Bitcoin daemon secret config
# Fill with your node RPC credentials.
rpcuser=REPLACE_WITH_BITCOIN_RPC_USER
rpcpassword=REPLACE_WITH_BITCOIN_RPC_PASSWORD
rpcconnect=127.0.0.1
rpcport=8332
```

Replace the placeholder values with the actual RPC credentials from `bitcoin.conf`:

```
rpcuser=YOUR_RPC_USERNAME
rpcpassword=YOUR_RPC_PASSWORD
rpcconnect=127.0.0.1
rpcport=8332
```

Then open `./secrets/bitcoin-wallet.conf`. It looks like this:

```
# KONKIN Bitcoin wallet secret config
# Fill with your wallet details.
wallet=REPLACE_WITH_BITCOIN_WALLET_NAME
wallet-passphrase=REPLACE_WITH_BITCOIN_WALLET_PASSPHRASE
```

Replace the placeholder values:

```
wallet=mywallet
wallet-passphrase=YOUR_WALLET_PASSPHRASE_IF_ENCRYPTED
```

If the wallet is not encrypted (no passphrase), leave the `wallet-passphrase` line as an empty value:

```
wallet-passphrase=
```

### Step 8d: Start Konkin and verify the wallet connection

Start the server again:

```bash
cd ~/konkin
java -jar konkin-server.jar config.toml
```

Open `http://127.0.0.1:7070` in a browser and log in. The landing page shows the status of connected wallets. Bitcoin should show as `AVAILABLE` or `SYNCING` (if the node is still catching up with the blockchain).

If it shows `OFFLINE`, the RPC credentials are wrong or Bitcoin Core is not running. Double-check `./secrets/bitcoin-daemon.conf` and make sure Bitcoin Core is running with `server=1`.

---

## Step 8M: Connect a Monero Wallet

Skip this step if the human did not enable Monero in `config.toml`.

Konkin connects to an existing Monero node (`monerod`) and wallet RPC service (`monero-wallet-rpc`) via JSON-RPC. The human needs both running.

### Step 8Ma: Install Monero (if not already running)

If the human does not have Monero installed, tell them to download it from `https://www.getmonero.org/downloads/` and follow the installation instructions for their OS.

The download includes both `monerod` (the daemon) and `monero-wallet-rpc` (the wallet RPC service). Both are needed.

### Step 8Mb: Start monerod

The human must have `monerod` running and synced. If they already have a running node, skip to Step 8Mc.

```bash
monerod --rpc-bind-ip=127.0.0.1 --rpc-bind-port=18081 --confirm-external-bind
```

The initial sync takes a long time (hours to days depending on hardware). The human can continue setup while it syncs, but the wallet will show as `SYNCING` in Konkin until the node is fully synced.

### Step 8Mc: Start monero-wallet-rpc

The human needs `monero-wallet-rpc` running and connected to their `monerod` instance. They must either open an existing wallet or create a new one.

To create a new wallet and start the RPC service:

```bash
monero-wallet-rpc --rpc-bind-ip=127.0.0.1 --rpc-bind-port=18082 \
  --rpc-login YOUR_RPC_USER:YOUR_RPC_PASSWORD \
  --daemon-address 127.0.0.1:18081 \
  --wallet-file ~/monero-wallets/mywallet --create-address-for-subaddress-account \
  --password YOUR_WALLET_PASSWORD
```

To open an existing wallet:

```bash
monero-wallet-rpc --rpc-bind-ip=127.0.0.1 --rpc-bind-port=18082 \
  --rpc-login YOUR_RPC_USER:YOUR_RPC_PASSWORD \
  --daemon-address 127.0.0.1:18081 \
  --wallet-file ~/monero-wallets/mywallet \
  --password YOUR_WALLET_PASSWORD
```

The human picks their own `YOUR_RPC_USER` and `YOUR_RPC_PASSWORD` values. They must remember them for the next step.

### Step 8Md: Configure Konkin to connect to Monero

Stop the Konkin server (Ctrl+C).

Open `./secrets/monero-daemon.conf`. It was auto-generated and looks like this:

```
# KONKIN Monero daemon secret config (monerod)
# Fill with your monerod RPC bind details.
rpc-bind-ip=127.0.0.1
rpc-bind-port=18081
# Optional:
# rpc-login=REPLACE_WITH_DAEMON_RPC_USER:REPLACE_WITH_DAEMON_RPC_PASSWORD
```

If `monerod` was started without `--rpc-login`, leave the `rpc-login` line commented out. If `monerod` requires authentication, uncomment and fill it in.

Then open `./secrets/monero-wallet-rpc.conf`. It looks like this:

```
# KONKIN Monero wallet-rpc secret config (monero-wallet-rpc)
# Fill with your monero-wallet-rpc credentials.
rpc-bind-ip=127.0.0.1
rpc-bind-port=18082
rpc-login=REPLACE_WITH_WALLET_RPC_USER:REPLACE_WITH_WALLET_RPC_PASSWORD
```

Replace the `rpc-login` placeholder with the actual credentials used when starting `monero-wallet-rpc`:

```
rpc-login=YOUR_RPC_USER:YOUR_RPC_PASSWORD
```

### Step 8Me: Start Konkin and verify the Monero wallet connection

Start the server again:

```bash
cd ~/konkin
java -jar konkin-server.jar config.toml
```

Open `http://127.0.0.1:7070` in a browser and log in. The landing page shows the status of connected wallets. Monero should show as `AVAILABLE` or `SYNCING` (if the node is still catching up with the blockchain).

If it shows `OFFLINE`, the RPC credentials are wrong or `monero-wallet-rpc` is not running. Double-check `./secrets/monero-wallet-rpc.conf` and make sure both `monerod` and `monero-wallet-rpc` are running.

---

## Step 9: Connect an AI Agent

Now that the server is running with Telegram and a wallet connected, the human can connect their AI agent.

### Step 9a: Read the agent credentials

```bash
cat ./secrets/agent-primary.secret
```

This shows:

```
client-id=konkin-primary
client-secret=SOME_LONG_HEX_STRING
```

Copy the `client-secret` value.

### Step 9b: Get a bearer token

```bash
curl -s -X POST "http://127.0.0.1:9550/oauth/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=konkin-primary" \
  -d "client_secret=PASTE_CLIENT_SECRET_HERE"
```

The response is JSON with an `access_token` field. Copy that token.

Notes:
- Tokens never expire.
- Maximum 2 active tokens per agent. A 3rd issuance evicts the oldest.
- If the human gets `429 rate_limited`, they must wait 60 seconds and retry with the correct secret.

### Step 9c: Register with Claude Code

```bash
claude mcp add --transport sse \
  -H "Authorization: Bearer PASTE_ACCESS_TOKEN_HERE" \
  -s project \
  konkin "http://127.0.0.1:9550/sse"
```

Then restart Claude Code.

### Step 9d: Verify the connection

After restarting, the AI agent should have access to Konkin MCP tools. The human can ask the agent to check the health resource `konkin://health`. It should return something like:

```json
{"status": "healthy", "agent": "...", "type": "driver"}
```

If the agent gets `401 Unauthorized`, the token is wrong. Repeat Step 9b.

---

## Summary of File Locations

After a complete setup, the human's `~/konkin/` directory looks like this:

```
~/konkin/
  konkin-server.jar          <-- the server
  config.toml                <-- main config
  secrets/
    agent-primary.secret     <-- driver agent credentials
    agent-arthur.secret      <-- auth agent credentials
    web-ui.password          <-- web UI login password
    rest-api.secret          <-- REST API key
    telegram.secret          <-- Telegram bot token and approved chat IDs
    bitcoin-daemon.conf      <-- Bitcoin Core RPC credentials (if Bitcoin enabled)
    bitcoin-wallet.conf      <-- Bitcoin wallet name and passphrase (if Bitcoin enabled)
    monero-daemon.conf       <-- Monero daemon RPC details (if Monero enabled)
    monero-wallet-rpc.conf   <-- Monero wallet-rpc credentials (if Monero enabled)
    db.secret                <-- auto-generated database password
  data/
    konkin.mv.db             <-- H2 database (auto-created)
  logs/
    konkin.log               <-- application log (auto-created)
```

---

## Troubleshooting

| Problem                                    | Fix                                                                                                            |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| `java: command not found`                  | Java is not installed. Go back to Step 3.                                                                      |
| `UnsupportedClassVersionError`             | Java version is too old. Install Java 21+.                                                                     |
| Server exits immediately with config error | Check `config.toml` for syntax errors. TOML is whitespace-sensitive around `=` signs.                          |
| `401 Unauthorized` on MCP                  | Re-issue the bearer token (Step 9b) and update the MCP registration.                                           |
| `429 rate_limited`                         | Wait 60 seconds, then retry with correct credentials.                                                          |
| Bitcoin shows `OFFLINE`                    | Bitcoin Core is not running, or RPC credentials in `./secrets/bitcoin-daemon.conf` are wrong.                  |
| Monero shows `OFFLINE`                     | `monero-wallet-rpc` is not running, or RPC credentials in `./secrets/monero-wallet-rpc.conf` are wrong.        |
| Telegram not sending approvals             | Check that `bot-token` in `./secrets/telegram.secret` is correct and at least one chat ID is approved.         |
| Web UI shows no wallets                    | No coins are enabled in `config.toml`, or the secret files still have placeholder values.                      |
| `Address already in use`                   | Another process is using port 7070 or 9550. Change the ports in `config.toml` or stop the other process.       |
| `gpg: BAD signature`                       | The downloaded `.jar` file was tampered with. Delete it and re-download from the official GitHub release page. |
