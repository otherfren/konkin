#!/bin/bash
# Interactive dependency updater for pom.xml
# Checks all dependencies and plugins for newer versions, then asks for each one.

set -euo pipefail

POM="pom.xml"
BACKUP="pom.xml.backup"

if [ ! -f "$POM" ]; then
    echo "Error: $POM not found. Run this script from the project root."
    exit 1
fi

echo "Checking for dependency updates (ignoring alpha/beta/rc)..."
echo ""

# Run versions plugin and capture output
UPDATE_OUTPUT=$(mvn versions:display-dependency-updates \
    versions:display-plugin-updates \
    versions:display-property-updates \
    -Dmaven.version.ignore=".*-alpha.*|.*-beta.*|.*-rc.*|.*-RC.*|.*-M[0-9].*|.*-cr.*|.*-SNAPSHOT.*" \
    -q 2>/dev/null || true)

# Parse dependency updates: "groupId:artifactId ... current -> latest"
UPDATES=$(echo "$UPDATE_OUTPUT" | grep -E '\.\.\.' | grep -E ' -> ' | sed 's/\[INFO\]//g' | sed 's/^[[:space:]]*//')

if [ -z "$UPDATES" ]; then
    echo "All dependencies are up to date."
    exit 0
fi

# Count updates
TOTAL=$(echo "$UPDATES" | wc -l)
echo "Found $TOTAL available update(s):"
echo ""
echo "$UPDATES"
echo ""
echo "==========================================="
echo ""

# Back up pom.xml before any changes
cp "$POM" "$BACKUP"
CHANGED=0

while IFS= read -r line; do
    # Extract artifact, current version, and latest version
    # Format: "  groupId:artifactId ........................ current -> latest"
    ARTIFACT=$(echo "$line" | awk '{print $1}')
    CURRENT=$(echo "$line" | grep -oP '[\d][^\s]*(?=\s*->)' | head -1)
    LATEST=$(echo "$line" | grep -oP '-> \K[^\s]+')

    if [ -z "$ARTIFACT" ] || [ -z "$CURRENT" ] || [ -z "$LATEST" ]; then
        continue
    fi

    GROUP_ID=$(echo "$ARTIFACT" | cut -d: -f1)
    ARTIFACT_ID=$(echo "$ARTIFACT" | cut -d: -f2)

    echo -e "\033[1m$GROUP_ID:$ARTIFACT_ID\033[0m"
    echo "  current: $CURRENT"
    echo "  latest:  $LATEST"
    echo ""
    read -rp "  Update $CURRENT -> $LATEST? [y/N/q] " answer

    case "$answer" in
        [yY]|[yY][eE][sS])
            # Check if version is managed via a property
            PROP_MATCH=$(grep -P "<version>\\\$\{[^}]+\}</version>" "$POM" | grep -B5 "$ARTIFACT_ID" || true)

            if grep -qP ">${CURRENT}<" "$POM"; then
                # Direct version replacement (handles both properties and inline versions)
                sed -i "s|>${CURRENT}<|>${LATEST}<|g" "$POM"
                echo -e "  \033[32m✓ Updated to $LATEST\033[0m"
                CHANGED=$((CHANGED + 1))
            else
                echo -e "  \033[33m⚠ Could not find version $CURRENT in pom.xml (may be a transitive dependency)\033[0m"
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
done <<< "$UPDATES"

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
