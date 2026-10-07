#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_MODULES='agentforge-ai-parent,agentforge-ai-bom,agentforge-model,agentforge-model/agentforge-model-api,agentforge-model/agentforge-model-core,agentforge-model/agentforge-model-openai,agentforge-model/agentforge-model-anthropic,agentforge-model/agentforge-model-registry,agentforge-framework,agentforge-framework/agentforge-agent-core,agentforge-framework/agentforge-harness-agent'

usage() {
    cat <<'EOF'
Usage: bin/local/release.sh <version> [options]

Options:
  --settings <file>  Maven settings.xml (default: $MAVEN_SETTINGS or ~/.m2/settings.xml)
  --dry-run          Build and sign the release artifacts without uploading
  --auto-publish     Upload and publish automatically instead of waiting in Portal
  --skip-tests       Skip tests (not recommended for a formal release)
  -h, --help         Show this help
EOF
}

[[ $# -gt 0 ]] || { usage; exit 1; }
if [[ "$1" == '-h' || "$1" == '--help' ]]; then
    usage
    exit 0
fi

VERSION="$1"
shift
SETTINGS_FILE="${MAVEN_SETTINGS:-${HOME}/.m2/settings.xml}"
DRY_RUN=0
AUTO_PUBLISH=0
SKIP_TESTS=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --settings)
            [[ $# -ge 2 ]] || { echo 'Error: --settings requires a file path.' >&2; exit 1; }
            SETTINGS_FILE="$2"
            shift 2
            ;;
        --dry-run) DRY_RUN=1; shift ;;
        --auto-publish) AUTO_PUBLISH=1; shift ;;
        --skip-tests) SKIP_TESTS=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Error: unknown option: $1" >&2; usage; exit 1 ;;
    esac
done

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$ ]] || {
    echo "Error: invalid release version: $VERSION" >&2
    exit 1
}
[[ "$VERSION" != *-SNAPSHOT ]] || { echo 'Error: a Central release cannot use -SNAPSHOT.' >&2; exit 1; }
[[ -f "$SETTINGS_FILE" ]] || { echo "Error: settings.xml not found: $SETTINGS_FILE" >&2; exit 1; }
command -v mvn >/dev/null || { echo 'Error: mvn was not found in PATH.' >&2; exit 1; }

# Resolve GPG explicitly because MacPorts/Homebrew bin directories are not always present in GUI Terminal PATH.
GPG_EXECUTABLE="${GPG_EXECUTABLE:-}"
if [[ -z "$GPG_EXECUTABLE" ]]; then
    for candidate in "$(command -v gpg 2>/dev/null || true)" /opt/local/bin/gpg /opt/homebrew/bin/gpg /usr/local/bin/gpg; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            GPG_EXECUTABLE="$candidate"
            break
        fi
    done
fi
[[ -n "$GPG_EXECUTABLE" ]] || { echo 'Error: gpg was not found.' >&2; exit 1; }
export GPG_TTY="${GPG_TTY:-$(tty)}"

if [[ -z "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
    printf 'GPG Passphrase (input hidden): '
    IFS= read -r -s MAVEN_GPG_PASSPHRASE
    printf '\n'
    [[ -n "$MAVEN_GPG_PASSPHRASE" ]] || { echo 'Error: GPG Passphrase cannot be empty.' >&2; exit 1; }
    export MAVEN_GPG_PASSPHRASE
fi

# Fail fast with GPG's original error before Maven starts the multi-module build.
SIGN_CHECK_FILE="$(mktemp /tmp/agentforge-gpg-check.XXXXXX)"
cleanup_sign_check() {
    rm -f "$SIGN_CHECK_FILE" "${SIGN_CHECK_FILE}.asc"
}
trap cleanup_sign_check EXIT
printf 'AgentForge Maven Central signing check\n' > "$SIGN_CHECK_FILE"
if ! printf '%s\n' "$MAVEN_GPG_PASSPHRASE" | "$GPG_EXECUTABLE" \
    --batch --yes --no-tty --pinentry-mode loopback --passphrase-fd 0 \
    --armor --detach-sign "$SIGN_CHECK_FILE"; then
    echo 'Error: GPG preflight signing failed. Enter the private-key Passphrase created with this GPG key.' >&2
    echo '       Do not enter the Central Token, KEY_ID, fingerprint, or macOS login password.' >&2
    exit 1
fi
"$GPG_EXECUTABLE" --batch --verify "${SIGN_CHECK_FILE}.asc" "$SIGN_CHECK_FILE"
cleanup_sign_check
trap - EXIT

MVN_ARGS=(
    -s "$SETTINGS_FILE"
    -P release
    "-Drevision=${VERSION}"
    "-Dgpg.executable=${GPG_EXECUTABLE}"
    -pl "$RELEASE_MODULES"
)
[[ "$SKIP_TESTS" -eq 1 ]] && MVN_ARGS+=(-DskipTests)

echo ">> version=$VERSION dryRun=$DRY_RUN autoPublish=$AUTO_PUBLISH"
echo ">> settings=$SETTINGS_FILE"
echo ">> gpg=$GPG_EXECUTABLE"

if [[ "$DRY_RUN" -eq 1 ]]; then
    mvn "${MVN_ARGS[@]}" clean verify
    echo '>> Dry run succeeded: artifacts were built and signed; nothing was uploaded.'
    exit 0
fi

if [[ "$AUTO_PUBLISH" -eq 1 ]]; then
    MVN_ARGS+=(-Dcentral.publishing.autoPublish=true -Dcentral.publishing.waitUntil=published)
fi

mvn "${MVN_ARGS[@]}" clean deploy

if [[ "$AUTO_PUBLISH" -eq 1 ]]; then
    echo '>> Central deployment was uploaded and automatic publishing was requested.'
else
    echo '>> Central deployment was uploaded. Review and publish it in the Portal:'
    echo '   https://central.sonatype.com/publishing/deployments'
fi
