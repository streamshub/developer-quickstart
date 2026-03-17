#!/usr/bin/env bash
#
# Uninstall the StreamsHub developer quick-start stack
#
# Safely tears down the event-streaming stack with per-operator-group
# shared-cluster safety checks to avoid deleting CRDs that are in use
# by other deployments.
#
# Usage:
#   # Uninstall from remote repository
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | bash
#
#   # Pin to a specific ref or fork
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | REF=v1.0.0 bash
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | REPO=myuser/developer-quickstart bash
#
# Environment variables:
#   LOCAL_DIR - Use local directory instead of GitHub (e.g. LOCAL_DIR=.)
#   REPO      - GitHub repo path (default: streamshub/developer-quickstart)
#   REF       - Git ref/branch/tag (default: main)
#   TIMEOUT   - kubectl wait/poll timeout (default: 120s)
#

set -euo pipefail

# Defaults (overridable via environment variables)
LOCAL_DIR="${LOCAL_DIR:-}"
REPO="${REPO:-streamshub/developer-quickstart}"
REF="${REF:-main}"
TIMEOUT="${TIMEOUT:-120s}"

QUICKSTART_LABEL="app.kubernetes.io/part-of=streamshub-developer-quickstart"

# Color output helpers
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# Build the kustomize URL (or local path) for a given sub-path
kustomize_url() {
    local path="$1"
    if [ -n "${LOCAL_DIR}" ]; then
        echo "${LOCAL_DIR}/${path}"
    else
        echo "https://github.com/${REPO}//${path}?ref=${REF}"
    fi
}

# Extract timeout value in seconds from TIMEOUT variable
timeout_seconds() {
    local val="${TIMEOUT}"
    # Strip trailing 's' if present
    val="${val%s}"
    echo "$val"
}

# Wait for all custom resources of a given type to be fully removed
# Returns 0 if all removed, 1 if timeout
wait_for_cr_removal() {
    local cr_type="$1"
    local namespace="$2"
    local max_wait
    max_wait=$(timeout_seconds)
    local elapsed=0

    while [ $elapsed -lt "$max_wait" ]; do
        local count
        count=$(kubectl get "$cr_type" -n "$namespace" -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | wc -l | tr -d ' ')
        if [ "$count" -eq 0 ]; then
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    return 1
}

# Check if any CRs of the given types exist WITHOUT the quick-start label.
# This detects shared-cluster usage.
# Arguments: cr_types (space-separated list of CR type names)
# Returns 0 if unlabeled CRs found (shared), 1 if none found (safe to delete)
has_unlabeled_crs() {
    local cr_types="$1"
    for cr_type in $cr_types; do
        local count
        count=$(kubectl get "$cr_type" -A --selector="!app.kubernetes.io/part-of" --no-headers 2>/dev/null | wc -l | tr -d ' ')
        if [ "$count" -gt 0 ]; then
            return 0
        fi
        # Also check for resources with the label set to a different value
        count=$(kubectl get "$cr_type" -A --no-headers 2>/dev/null | wc -l | tr -d ' ')
        local labeled_count
        labeled_count=$(kubectl get "$cr_type" -A -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | wc -l | tr -d ' ')
        if [ "$count" -gt "$labeled_count" ]; then
            return 0
        fi
    done
    return 1
}

# Strimzi CR types to check for shared usage
STRIMZI_CR_TYPES="kafkas kafkanodepools kafkatopics kafkausers kafkaconnects kafkamirrormakers kafkamirrormaker2s kafkabridges kafkaconnectors kafkarebalances"

# Apicurio Registry CR types
APICURIO_CR_TYPES="apicurioregistry3s"

# StreamsHub Console CR types
CONSOLE_CR_TYPES="consoles"

main() {
    echo ""
    info "Uninstalling StreamsHub developer quick-start stack"
    if [ -n "${LOCAL_DIR}" ]; then
        info "Dir: ${LOCAL_DIR} | Timeout: ${TIMEOUT}"
    else
        info "Repo: ${REPO} | Ref: ${REF} | Timeout: ${TIMEOUT}"
    fi
    echo ""

    # Track which operator groups have shared CRDs
    local strimzi_shared=false
    local apicurio_shared=false
    local console_shared=false

    # --- Phase 1: Delete operands (stack layer) ---
    local stack_url
    stack_url=$(kustomize_url "stack")
    info "Phase 1: Deleting operands..."
    info "Deleting: ${stack_url}"
    kubectl delete -k "${stack_url}" --ignore-not-found=true 2>/dev/null || true
    echo ""

    # --- Wait for CRs to be fully removed ---
    info "Waiting for custom resources to be removed (finalizers may take time)..."

    local removal_failed=false

    # Wait for Kafka resources
    if kubectl get kafka -n kafka -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | grep -q .; then
        info "  Waiting for Kafka resource removal in namespace 'kafka'..."
        if ! wait_for_cr_removal "kafka" "kafka"; then
            warn "  Kafka resources not fully removed within timeout"
            removal_failed=true
        else
            info "  Kafka resources removed"
        fi
    fi

    # Wait for Apicurio Registry resources
    if kubectl get apicurioregistry3 -n apicurio-registry -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | grep -q .; then
        info "  Waiting for Apicurio Registry resource removal in namespace 'apicurio-registry'..."
        if ! wait_for_cr_removal "apicurioregistry3" "apicurio-registry"; then
            warn "  Apicurio Registry resources not fully removed within timeout"
            removal_failed=true
        else
            info "  Apicurio Registry resources removed"
        fi
    fi

    # Wait for Console resources
    if kubectl get console.console.streamshub.github.com -n streamshub-console -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | grep -q .; then
        info "  Waiting for Console resource removal in namespace 'streamshub-console'..."
        if ! wait_for_cr_removal "console.console.streamshub.github.com" "streamshub-console"; then
            warn "  Console resources not fully removed within timeout"
            removal_failed=true
        else
            info "  Console resources removed"
        fi
    fi

    if [ "$removal_failed" = true ]; then
        warn "Some custom resources were not fully removed. Proceeding with caution..."
    fi
    echo ""

    # --- Phase 2: Shared-cluster safety checks ---
    info "Phase 2: Checking for shared CRD usage..."

    if has_unlabeled_crs "$STRIMZI_CR_TYPES"; then
        strimzi_shared=true
        warn "  Strimzi: Found non-quick-start CRs — CRDs will be retained"
    else
        info "  Strimzi: No shared CRs found — safe for full removal"
    fi

    if has_unlabeled_crs "$APICURIO_CR_TYPES"; then
        apicurio_shared=true
        warn "  Apicurio Registry: Found non-quick-start CRs — CRDs will be retained"
    else
        info "  Apicurio Registry: No shared CRs found — safe for full removal"
    fi

    if has_unlabeled_crs "$CONSOLE_CR_TYPES"; then
        console_shared=true
        warn "  StreamsHub Console: Found non-quick-start CRs — CRDs will be retained"
    else
        info "  StreamsHub Console: No shared CRs found — safe for full removal"
    fi
    echo ""

    # --- Phase 3: Delete operators and CRDs ---
    if [ "$strimzi_shared" = false ] && [ "$apicurio_shared" = false ] && [ "$console_shared" = false ]; then
        # No shared CRDs — safe to delete the entire base layer
        local base_url
        base_url=$(kustomize_url "base")
        info "Phase 3: Deleting operators and CRDs (no shared usage detected)..."
        info "Deleting: ${base_url}"
        kubectl delete -k "${base_url}" --ignore-not-found=true 2>/dev/null || true
    else
        # Some operator groups have shared CRDs — selective cleanup
        info "Phase 3: Selective operator removal (some CRDs are shared)..."

        if [ "$strimzi_shared" = false ]; then
            info "  Removing Strimzi operator (full removal including CRDs)..."
            local strimzi_url
            strimzi_url=$(kustomize_url "base/strimzi-operator")
            kubectl delete -k "${strimzi_url}" --ignore-not-found=true 2>/dev/null || true
        else
            warn "  Strimzi: Removing operator deployment only (retaining CRDs)..."
            kubectl delete deployment strimzi-cluster-operator -n strimzi --ignore-not-found=true 2>/dev/null || true
            kubectl delete serviceaccount strimzi-cluster-operator -n strimzi --ignore-not-found=true 2>/dev/null || true
            kubectl delete configmap strimzi-cluster-operator -n strimzi --ignore-not-found=true 2>/dev/null || true
        fi

        if [ "$apicurio_shared" = false ]; then
            info "  Removing Apicurio Registry operator (full removal including CRDs)..."
            local apicurio_url
            apicurio_url=$(kustomize_url "base/apicurio-registry-operator")
            kubectl delete -k "${apicurio_url}" --ignore-not-found=true 2>/dev/null || true
        else
            warn "  Apicurio Registry: Removing operator deployment only (retaining CRDs)..."
            kubectl delete deployment apicurio-registry-operator -n apicurio-registry --ignore-not-found=true 2>/dev/null || true
            kubectl delete serviceaccount apicurio-registry-operator -n apicurio-registry --ignore-not-found=true 2>/dev/null || true
        fi

        if [ "$console_shared" = false ]; then
            info "  Removing StreamsHub Console operator (full removal including CRDs)..."
            local console_url
            console_url=$(kustomize_url "base/streamshub-console-operator")
            kubectl delete -k "${console_url}" --ignore-not-found=true 2>/dev/null || true
        else
            warn "  StreamsHub Console: Removing operator deployment only (retaining CRDs)..."
            kubectl delete deployment console-operator -n streamshub-console --ignore-not-found=true 2>/dev/null || true
            kubectl delete serviceaccount console-operator -n streamshub-console --ignore-not-found=true 2>/dev/null || true
        fi
    fi
    echo ""

    # --- Summary ---
    info "Uninstall complete!"
    echo ""

    # Report retained groups
    if [ "$strimzi_shared" = true ] || [ "$apicurio_shared" = true ] || [ "$console_shared" = true ]; then
        echo "Retained operator groups (shared CRDs detected):"
        [ "$strimzi_shared" = true ] && echo "  - Strimzi CRDs (non-quick-start Kafka resources exist on the cluster)"
        [ "$apicurio_shared" = true ] && echo "  - Apicurio Registry CRDs (non-quick-start Registry resources exist on the cluster)"
        [ "$console_shared" = true ] && echo "  - StreamsHub Console CRDs (non-quick-start Console resources exist on the cluster)"
        echo ""
        echo "To manually remove retained CRDs after verifying no resources depend on them:"
        echo "  kubectl get crds -l ${QUICKSTART_LABEL}"
        echo ""
    fi

    # Check for any remaining quick-start resources
    local remaining
    remaining=$(kubectl get all -A -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | wc -l | tr -d ' ')
    local remaining_cluster
    remaining_cluster=$(kubectl get crds,clusterroles,clusterrolebindings -l "$QUICKSTART_LABEL" --no-headers 2>/dev/null | wc -l | tr -d ' ')

    if [ "$remaining" -gt 0 ] || [ "$remaining_cluster" -gt 0 ]; then
        warn "Some quick-start resources remain on the cluster:"
        [ "$remaining" -gt 0 ] && kubectl get all -A -l "$QUICKSTART_LABEL" 2>/dev/null
        [ "$remaining_cluster" -gt 0 ] && kubectl get crds,clusterroles,clusterrolebindings -l "$QUICKSTART_LABEL" 2>/dev/null
    else
        info "All quick-start resources have been removed"
    fi
}

main
