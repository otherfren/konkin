#!/bin/bash
# Usage: ./scripts/interactive_update.sh          — interactive mode, asks per dependency
#        yes | ./scripts/interactive_update.sh     — auto-accept all updates

set -euo pipefail

POM="pom.xml"
BACKUP="pom.xml.backup"

STRIP_ANSI='s/\x1b\[[0-9;]*m//g'
VERSION_IGNORE=".*-alpha.*|.*-beta.*|.*-rc.*|.*-RC.*|.*-M[0-9].*|.*-cr.*|.*-SNAPSHOT.*"

if [ ! -f "$POM" ]; then
    echo "Error: $POM not found. Run this script from the project root."
    exit 1
fi

echo "Checking for dependency updates (ignoring alpha/beta/rc/snapshot)..."
echo ""

# --- Step 1: dependency updates ---
echo "[1/3] Checking dependency updates ..."
DEP_RAW=$(mvn versions:display-dependency-updates \
    -Dmaven.version.ignore="$VERSION_IGNORE" \
    2>&1 | sed "$STRIP_ANSI" || true)
DEP_UPDATES=$(echo "$DEP_RAW" | grep -E '\.\.\.' | grep -E ' -> ' | sed 's/\[INFO\]//g; s/^[[:space:]]*//' || true)

if [ -n "$DEP_UPDATES" ]; then
    echo "  $(echo "$DEP_UPDATES" | wc -l) dependency update(s) found"
else
    echo "  all dependencies are up to date"
fi

# --- Step 2: property updates ---
echo "[2/3] Checking property updates ..."
PROP_RAW=$(mvn versions:display-property-updates \
    -Dmaven.version.ignore="$VERSION_IGNORE" \
    2>&1 | sed "$STRIP_ANSI" || true)
PROP_UPDATES=$(echo "$PROP_RAW" | grep -E '\.\.\.' | grep -E ' -> ' | sed 's/\[INFO\]//g; s/^[[:space:]]*//' || true)

if [ -n "$PROP_UPDATES" ]; then
    echo "  $(echo "$PROP_UPDATES" | wc -l) property update(s) found"
else
    echo "  all properties are up to date"
fi

# --- Step 3: plugin updates (only for current Maven version) ---
echo "[3/3] Checking plugin updates ..."
PLUGIN_RAW=$(mvn versions:display-plugin-updates \
    -Dmaven.version.ignore="$VERSION_IGNORE" \
    2>&1 | sed "$STRIP_ANSI" || true)

# The plugin output shows version ranges grouped by required Maven version.
# Only keep the last group (matching our current Maven version) to avoid downgrades.
# Also skip lines where "latest" < "current" (the output includes historical versions).
CURRENT_MAVEN_SECTION=""
while IFS= read -r pline; do
    if echo "$pline" | grep -qE 'Require Maven [0-9]'; then
        CURRENT_MAVEN_SECTION="$pline"
    fi
done < <(echo "$PLUGIN_RAW" | grep -E 'Require Maven' || true)

PLUGIN_UPDATES=""
if [ -n "$CURRENT_MAVEN_SECTION" ]; then
    # Extract the Maven version from the last "Require Maven X.Y.Z" header
    REQUIRED_MAVEN=$(echo "$CURRENT_MAVEN_SECTION" | grep -oP 'Require Maven \K[0-9.]+')
    # Get lines after the last "Require Maven" section until a blank [INFO] line
    PLUGIN_UPDATES=$(echo "$PLUGIN_RAW" \
        | sed -n "/Require Maven ${REQUIRED_MAVEN}/,/^\[INFO\] *$/p" \
        | grep -E '\.\.\.' | grep -E ' -> ' \
        | sed 's/\[INFO\]//g; s/^[[:space:]]*//' || true)
fi

if [ -n "$PLUGIN_UPDATES" ]; then
    echo "  $(echo "$PLUGIN_UPDATES" | wc -l) plugin update(s) found (for Maven >= ${REQUIRED_MAVEN:-?})"
else
    echo "  all plugins are up to date"
fi

# --- Combine all updates, deduplicate ---
ALL_UPDATES=""
[ -n "$DEP_UPDATES" ] && ALL_UPDATES="$DEP_UPDATES"
[ -n "$PROP_UPDATES" ] && { [ -n "$ALL_UPDATES" ] && ALL_UPDATES="$ALL_UPDATES"$'\n'"$PROP_UPDATES" || ALL_UPDATES="$PROP_UPDATES"; }
[ -n "$PLUGIN_UPDATES" ] && { [ -n "$ALL_UPDATES" ] && ALL_UPDATES="$ALL_UPDATES"$'\n'"$PLUGIN_UPDATES" || ALL_UPDATES="$PLUGIN_UPDATES"; }

# Deduplicate (keep last occurrence of each artifact for plugins)
ALL_UPDATES=$(echo "$ALL_UPDATES" | awk '{key=$1} !seen[key]++' || true)

if [ -z "$ALL_UPDATES" ]; then
    echo ""
    echo "Everything is up to date."
    exit 0
fi

TOTAL=$(echo "$ALL_UPDATES" | wc -l)
echo ""
echo "==========================================="
echo " $TOTAL available update(s):"
echo "==========================================="
echo ""
echo "$ALL_UPDATES"
echo ""
echo "==========================================="
echo ""

# Back up pom.xml before any changes
cp "$POM" "$BACKUP"
CHANGED=0

while IFS= read -r line; do
    # Extract artifact, current version, and latest version
    # Format: "groupId:artifactId ........... current -> latest"
    #     or: "${property.name} ............ current -> latest"
    ARTIFACT=$(echo "$line" | awk '{print $1}')
    CURRENT=$(echo "$line" | grep -oP '[0-9][^\s]*(?=\s*->)' | head -1)
    LATEST=$(echo "$line" | grep -oP -- '->\s*\K[^\s]+')

    if [ -z "$ARTIFACT" ] || [ -z "$CURRENT" ] || [ -z "$LATEST" ]; then
        echo "  [skip] could not parse: $line"
        continue
    fi

    # Display name: strip ${...} wrapper for properties
    DISPLAY_NAME="$ARTIFACT"

    echo -e "\033[1m$DISPLAY_NAME\033[0m"
    echo "  current: $CURRENT"
    echo "  latest:  $LATEST"
    echo ""
    read -rp "  Update $CURRENT -> $LATEST? [y/N/q] " answer

    case "$answer" in
        [yY]|[yY][eE][sS])
            if grep -q ">${CURRENT}<" "$POM"; then
                sed -i "s|>${CURRENT}<|>${LATEST}<|g" "$POM"
                echo -e "  \033[32m✓ Updated to $LATEST\033[0m"
                CHANGED=$((CHANGED + 1))
            else
                echo -e "  \033[33m⚠ Could not find version $CURRENT in pom.xml (may be transitive or managed)\033[0m"
            fi
            ;;
        [qQ])
            echo ""
            echo "Aborted."
            if [ "$CHANGED" -eq 0 ]; then
                rm -f "$BACKUP"
            else
                echo "$CHANGED update(s) applied. Backup saved to $BACKUP"
            fi
            exit 0
            ;;
        *)
            echo -e "  \033[90mSkipped\033[0m"
            ;;
    esac
    echo ""
done <<< "$ALL_UPDATES"

echo "==========================================="
if [ "$CHANGED" -gt 0 ]; then
    echo "$CHANGED update(s) applied."
    echo "Backup saved to $BACKUP"
    echo ""
    echo "Run 'mvn compile' to verify the updates build correctly."
    echo "Run 'diff $BACKUP $POM' to review changes."
else
    echo "No updates applied."
    rm -f "$BACKUP"
fi
