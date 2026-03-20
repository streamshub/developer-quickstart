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

# Discover CR type plural names for CRDs whose API group ends with the given suffix.
# Arguments: api_group_suffix (e.g. "strimzi.io"), all_crds (pre-fetched CRD data)
# Output: space-separated list of plural CR type names, or empty string
filter_cr_types() {
    local suffix="$1"
    local all_crds="$2"
    echo "$all_crds" \
        | awk -v suf="$suffix" 'length($1) >= length(suf) && substr($1, length($1) - length(suf) + 1) == suf { printf "%s ", $2 }' \
        | sed 's/ $//'
}

# Check if /dev/tty is available for interactive prompts.
# Needed for `curl | bash` where stdin is the pipe.
is_interactive() {
    [ -c /dev/tty ] 2>/dev/null
}

# Prompt user with a yes/no question via /dev/tty. Defaults to No.
# Arguments: prompt message
# Returns 0 for yes, 1 for no
prompt_yes_no() {
    local prompt="$1"
    local answer
    echo -en "${YELLOW}${prompt} [y/N]: ${NC}" > /dev/tty
    read -r answer < /dev/tty
    case "$answer" in
        [yY]|[yY][eE][sS]) return 0 ;;
        *) return 1 ;;
    esac
}

# List unlabeled CRs for given CR types across all namespaces.
# Arguments: cr_types (space-separated list)
# Returns 0 if any unlabeled CRs found, 1 if none
list_unlabeled_crs() {
    local cr_types="$1"
    local found=false
    for cr_type in $cr_types; do
        local count
        count=$(kubectl get "$cr_type" -A --selector='!app.kubernetes.io/part-of' --no-headers 2>/dev/null | wc -l | tr -d ' ') || count=0
        if [ "$count" -gt 0 ]; then
            found=true
            kubectl get "$cr_type" -A --selector='!app.kubernetes.io/part-of' 2>/dev/null || true
        fi
    done
    [ "$found" = true ]
}

# Delete unlabeled CRs for given CR types across all namespaces.
# Arguments: cr_types (space-separated list)
delete_unlabeled_crs() {
    local cr_types="$1"
    for cr_type in $cr_types; do
        local count
        count=$(kubectl get "$cr_type" -A --selector='!app.kubernetes.io/part-of' --no-headers 2>/dev/null | wc -l | tr -d ' ') || count=0
        if [ "$count" -gt 0 ]; then
            kubectl delete "$cr_type" -A --selector='!app.kubernetes.io/part-of' --ignore-not-found=true --wait=false 2>/dev/null || true
        fi
    done
}

# Wait for unlabeled CRs of given types to be fully removed.
# Arguments: cr_types (space-separated list)
# Returns 0 if all removed, 1 if timeout
wait_for_unlabeled_cr_removal() {
    local cr_types="$1"
    local max_wait
    max_wait=$(timeout_seconds)
    local elapsed=0

    while [ $elapsed -lt "$max_wait" ]; do
        local total=0
        for cr_type in $cr_types; do
            local count
            count=$(kubectl get "$cr_type" -A --selector='!app.kubernetes.io/part-of' --no-headers 2>/dev/null | wc -l | tr -d ' ')
            total=$((total + count))
        done
        if [ "$total" -eq 0 ]; then
            return 0
        fi
        if [ "${interrupted:-false}" = true ]; then
            return 1
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    return 1
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
        if [ "${interrupted:-false}" = true ]; then
            return 1
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

# API group suffixes used to discover CR types dynamically from installed CRDs
STRIMZI_API_GROUP_SUFFIX="strimzi.io"
APICURIO_API_GROUP_SUFFIX="apicur.io"
CONSOLE_API_GROUP_SUFFIX="streamshub.github.com"

main() {
    echo ""
    info "Uninstalling StreamsHub developer quick-start stack"
    if [ -n "${LOCAL_DIR}" ]; then
        info "Dir: ${LOCAL_DIR} | Timeout: ${TIMEOUT}"
    else
        info "Repo: ${REPO} | Ref: ${REF} | Timeout: ${TIMEOUT}"
    fi
    echo ""

    # Discover CR types dynamically from installed CRDs
    local all_crds
    all_crds=$(kubectl get crds \
        -o jsonpath='{range .items[*]}{.spec.group}{" "}{.spec.names.plural}{"\n"}{end}' \
        2>/dev/null || true)

    STRIMZI_CR_TYPES=$(filter_cr_types "$STRIMZI_API_GROUP_SUFFIX" "$all_crds")
    APICURIO_CR_TYPES=$(filter_cr_types "$APICURIO_API_GROUP_SUFFIX" "$all_crds")
    CONSOLE_CR_TYPES=$(filter_cr_types "$CONSOLE_API_GROUP_SUFFIX" "$all_crds")

    info "Discovered CR types:"
    info "  Strimzi: ${STRIMZI_CR_TYPES:-<none>}"
    info "  Apicurio Registry: ${APICURIO_CR_TYPES:-<none>}"
    info "  StreamsHub Console: ${CONSOLE_CR_TYPES:-<none>}"
    echo ""

    # Catch Ctrl+C so the shell doesn't silently exit before reaching interactive prompts
    local interrupted=false
    trap 'warn "Caught interrupt, continuing cleanup..."; interrupted=true' INT

    # Track which operator groups have shared CRDs
    local strimzi_shared=false
    local apicurio_shared=false
    local console_shared=false

    # --- Phase 1: Interactive cleanup of user-created CRs ---
    # Must run BEFORE stack deletion so operators are still alive to process finalizers
    info "Phase 1: Checking for user-created custom resources..."

    local has_user_crs=false

    # Check each operator group for unlabeled CRs
    local strimzi_user_crs=false
    local apicurio_user_crs=false
    local console_user_crs=false

    if list_unlabeled_crs "$STRIMZI_CR_TYPES" > /dev/null 2>&1; then
        strimzi_user_crs=true
        has_user_crs=true
    fi
    if list_unlabeled_crs "$APICURIO_CR_TYPES" > /dev/null 2>&1; then
        apicurio_user_crs=true
        has_user_crs=true
    fi
    if list_unlabeled_crs "$CONSOLE_CR_TYPES" > /dev/null 2>&1; then
        console_user_crs=true
        has_user_crs=true
    fi

    if [ "$has_user_crs" = true ]; then
        if is_interactive; then
            warn "Found user-created custom resources (not part of the quick-start)."
            warn "These resources will cause operator CRDs to be retained if not deleted."
            echo ""

            if [ "$strimzi_user_crs" = true ]; then
                info "  Strimzi resources without quick-start label:"
                list_unlabeled_crs "$STRIMZI_CR_TYPES" 2>/dev/null | sed 's/^/    /'
                echo ""
                if prompt_yes_no "  Delete these Strimzi resources?"; then
                    info "  Deleting unlabeled Strimzi resources..."
                    delete_unlabeled_crs "$STRIMZI_CR_TYPES"
                    if wait_for_unlabeled_cr_removal "$STRIMZI_CR_TYPES"; then
                        info "  Strimzi resources removed"
                        strimzi_user_crs=false
                    else
                        warn "  Some Strimzi resources were not fully removed within timeout"
                    fi
                else
                    info "  Skipping Strimzi resource deletion"
                fi
                echo ""
            fi

            if [ "$apicurio_user_crs" = true ]; then
                info "  Apicurio Registry resources without quick-start label:"
                list_unlabeled_crs "$APICURIO_CR_TYPES" 2>/dev/null | sed 's/^/    /'
                echo ""
                if prompt_yes_no "  Delete these Apicurio Registry resources?"; then
                    info "  Deleting unlabeled Apicurio Registry resources..."
                    delete_unlabeled_crs "$APICURIO_CR_TYPES"
                    if wait_for_unlabeled_cr_removal "$APICURIO_CR_TYPES"; then
                        info "  Apicurio Registry resources removed"
                        apicurio_user_crs=false
                    else
                        warn "  Some Apicurio Registry resources were not fully removed within timeout"
                    fi
                else
                    info "  Skipping Apicurio Registry resource deletion"
                fi
                echo ""
            fi

            if [ "$console_user_crs" = true ]; then
                info "  StreamsHub Console resources without quick-start label:"
                list_unlabeled_crs "$CONSOLE_CR_TYPES" 2>/dev/null | sed 's/^/    /'
                echo ""
                if prompt_yes_no "  Delete these StreamsHub Console resources?"; then
                    info "  Deleting unlabeled Console resources..."
                    delete_unlabeled_crs "$CONSOLE_CR_TYPES"
                    if wait_for_unlabeled_cr_removal "$CONSOLE_CR_TYPES"; then
                        info "  Console resources removed"
                        console_user_crs=false
                    else
                        warn "  Some Console resources were not fully removed within timeout"
                    fi
                else
                    info "  Skipping Console resource deletion"
                fi
                echo ""
            fi
        else
            warn "Non-interactive mode: skipping user-created resource cleanup."
            warn "User-created CRs were detected but cannot prompt for deletion."
            warn "Phase 3 will treat these as shared resources and retain their CRDs."
            echo ""
        fi
    else
        info "  No user-created custom resources found"
    fi
    echo ""

    # --- Phase 2: Delete operands (stack layer) ---
    local stack_url
    stack_url=$(kustomize_url "stack")
    info "Phase 2: Deleting operands..."
    info "Deleting: ${stack_url}"
    kubectl delete -k "${stack_url}" --ignore-not-found=true --wait=false 2>/dev/null || true
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
    interrupted=false
    echo ""

    # --- Phase 3: Shared-cluster safety checks ---
    info "Phase 3: Checking for shared CRD usage..."

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

    # --- Phase 4: Delete operators and CRDs ---
    if [ "$strimzi_shared" = false ] && [ "$apicurio_shared" = false ] && [ "$console_shared" = false ]; then
        # No shared CRDs — safe to delete the entire base layer
        local base_url
        base_url=$(kustomize_url "base")
        info "Phase 4: Deleting operators and CRDs (no shared usage detected)..."
        info "Deleting: ${base_url}"
        kubectl delete -k "${base_url}" --ignore-not-found=true 2>/dev/null || true
    else
        # Some operator groups have shared CRDs — selective cleanup
        info "Phase 4: Selective operator removal (some CRDs are shared)..."

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
