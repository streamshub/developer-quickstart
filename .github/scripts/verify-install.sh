#!/usr/bin/env bash
#
# Verify that all expected deployments and custom resources are ready.
# Reads component definitions from an overlay config file.
#
# Environment variables:
#   OVERLAY  - overlay name (default: "core")
#   TIMEOUT  - kubectl wait timeout (default: "600s")
#

set -euo pipefail

OVERLAY="${OVERLAY:-core}"
TIMEOUT="${TIMEOUT:-600s}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/../config/overlays/${OVERLAY}.env"

if [ ! -f "${CONFIG_FILE}" ]; then
    echo "ERROR: Overlay config not found: ${CONFIG_FILE}"
    exit 1
fi

# shellcheck disable=SC1090
source "${CONFIG_FILE}"

echo "=== Verifying install (overlay: ${OVERLAY}) ==="
echo ""

for entry in ${OPERATORS}; do
    ns="${entry%%:*}"
    deploy="${entry#*:}"
    echo "--- ${deploy} (${ns}) ---"
    kubectl get deployment -n "${ns}" "${deploy}"
done

echo ""

for entry in ${CUSTOM_RESOURCES}; do
    ns="${entry%%:*}"
    resource="${entry#*:}"
    echo "--- ${resource} (${ns}) ---"
    kubectl wait "${resource}" --for=condition=Ready -n "${ns}" --timeout="${TIMEOUT}"
done
