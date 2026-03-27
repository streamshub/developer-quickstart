#!/usr/bin/env bash
#
# Dump diagnostic information for debugging failed smoke tests.
# Reads component definitions from an overlay config file.
#
# Environment variables:
#   OVERLAY - overlay name (default: "core")
#

set +e

OVERLAY="${OVERLAY:-core}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/../config/overlays/${OVERLAY}.env"

if [ ! -f "${CONFIG_FILE}" ]; then
    echo "ERROR: Overlay config not found: ${CONFIG_FILE}"
    exit 1
fi

# shellcheck disable=SC1090
source "${CONFIG_FILE}"

echo "=== CR status ==="
for entry in ${CUSTOM_RESOURCES}; do
    ns="${entry%%:*}"
    resource="${entry#*:}"
    kubectl get "${resource}" -n "${ns}" -o yaml 2>/dev/null || true
done
echo ""
echo "=== Events (all namespaces) ==="
kubectl get events --all-namespaces --sort-by='.lastTimestamp' | tail -50
echo ""
echo "=== Pods (all namespaces) ==="
kubectl get pods --all-namespaces
echo ""
for ns in ${NAMESPACES}; do
    echo "=== Pods in ${ns} ==="
    kubectl get pods -n "${ns}" -o wide 2>/dev/null || true
    echo "=== Pod logs in ${ns} ==="
    for pod in $(kubectl get pods -n "${ns}" -o name 2>/dev/null); do
        echo "--- ${pod} ---"
        kubectl logs "${pod}" -n "${ns}" --tail=30 2>/dev/null || true
    done
done
