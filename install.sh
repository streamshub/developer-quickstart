#!/usr/bin/env bash
#
# Install the StreamsHub developer quick-start stack
#
# Deploys the full event-streaming stack: Strimzi Kafka Operator, Apicurio
# Registry and StreamsHub Console on a Kubernetes cluster.
#
# Usage:
#   # Install from remote repository
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | bash
#
#   # Pin to a specific ref or fork
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | REF=v1.0.0 bash
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | REPO=myuser/developer-quickstart bash
#
#   # Install with Prometheus metrics overlay
#   curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=metrics bash
#
# Environment variables:
#   LOCAL_DIR - Use local directory instead of GitHub (e.g. LOCAL_DIR=.)
#   REPO      - GitHub repo path (default: streamshub/developer-quickstart)
#   REF       - Git ref/branch/tag (default: main)
#   OVERLAY   - Overlay to apply (e.g. metrics). Empty = base install
#   TIMEOUT   - kubectl wait timeout (default: 120s)
#

set -euo pipefail

# Defaults (overridable via environment variables)
LOCAL_DIR="${LOCAL_DIR:-}"
REPO="${REPO:-streamshub/developer-quickstart}"
REF="${REF:-main}"
OVERLAY="${OVERLAY:-}"
TIMEOUT="${TIMEOUT:-120s}"

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

# Check prerequisites
check_prerequisites() {
    if ! command -v kubectl &> /dev/null; then
        error "kubectl is not installed or not in PATH"
        error "Install kubectl: https://kubernetes.io/docs/tasks/tools/"
        exit 1
    fi
    info "kubectl found: $(kubectl version --client 2>/dev/null | head -1)"

    # StreamsHub Console requires an Ingress controller
    if ! kubectl get ingressclass &> /dev/null || [ -z "$(kubectl get ingressclass -o name 2>/dev/null)" ]; then
        warn "No IngressClass found in the cluster"
        warn "StreamsHub Console requires an Ingress controller (e.g. 'minikube addons enable ingress')"
    fi
}

main() {
    # Compute kustomize paths based on overlay
    local base_path="overlays/core/base"
    local stack_path="overlays/core/stack"
    if [ -n "${OVERLAY}" ]; then
        base_path="overlays/${OVERLAY}/base"
        stack_path="overlays/${OVERLAY}/stack"
    fi

    # Compute total steps based on overlay
    local total_steps=5
    if [ "${OVERLAY}" = "metrics" ]; then
        total_steps=6
    fi

    echo ""
    info "Installing StreamsHub developer quick-start stack"
    if [ -n "${OVERLAY}" ]; then
        info "Overlay: ${OVERLAY}"
    fi
    if [ -n "${LOCAL_DIR}" ]; then
        info "Dir: ${LOCAL_DIR} | Timeout: ${TIMEOUT}"
    else
        info "Repo: ${REPO} | Ref: ${REF} | Timeout: ${TIMEOUT}"
    fi
    echo ""

    # --- Prerequisites ---
    check_prerequisites
    echo ""

    local step=1

    # --- Step: Install operators (base layer) ---
    local base_url
    base_url=$(kustomize_url "${base_path}")
    info "Step ${step}/${total_steps}: Installing operators and CRDs..."
    info "Applying: ${base_url}"
    # Server-side apply avoids annotation size limits with large CRDs
    # (e.g. Prometheus Operator CRDs exceed the 262144-byte limit for
    # the kubectl.kubernetes.io/last-applied-configuration annotation).
    kubectl apply --server-side --force-conflicts -k "${base_url}"
    echo ""

    # --- Step: Wait for prometheus-operator (metrics overlay only) ---
    if [ "${OVERLAY}" = "metrics" ]; then
        step=$((step + 1))
        info "Step ${step}/${total_steps}: Waiting for prometheus-operator to be ready (timeout: ${TIMEOUT})..."
        kubectl wait --for=condition=Available deployment/prometheus-operator \
            -n monitoring --timeout="${TIMEOUT}"
        info "Prometheus operator is ready"
        echo ""
    fi

    # --- Step: Wait for Strimzi operator ---
    step=$((step + 1))
    info "Step ${step}/${total_steps}: Waiting for strimzi-cluster-operator to be ready (timeout: ${TIMEOUT})..."
    kubectl wait --for=condition=Available deployment/strimzi-cluster-operator \
        -n strimzi --timeout="${TIMEOUT}"
    info "Strimzi operator is ready"
    echo ""

    # --- Step: Wait for Apicurio Registry operator ---
    step=$((step + 1))
    info "Step ${step}/${total_steps}: Waiting for apicurio-registry-operator to be ready (timeout: ${TIMEOUT})..."
    kubectl wait --for=condition=Available deployment/apicurio-registry-operator \
        -n apicurio-registry --timeout="${TIMEOUT}"
    info "Apicurio Registry operator is ready"
    echo ""

    # --- Step: Wait for StreamsHub Console operator ---
    step=$((step + 1))
    info "Step ${step}/${total_steps}: Waiting for console-operator to be ready (timeout: ${TIMEOUT})..."
    kubectl wait --for=condition=Available deployment/streamshub-console-operator \
        -n streamshub-console --timeout="${TIMEOUT}"
    info "StreamsHub Console operator is ready"
    echo ""

    # --- Step: Install operands (stack layer) ---
    step=$((step + 1))
    local stack_url
    stack_url=$(kustomize_url "${stack_path}")
    info "Step ${step}/${total_steps}: Installing operands (Kafka cluster, Registry instance, Console)..."
    info "Applying: ${stack_url}"
    kubectl apply -k "${stack_url}"
    echo ""

    # --- Summary ---
    info "Dev stack installation complete!"
    echo ""
    echo "Deployed components:"
    echo "  - Strimzi operator            (namespace: strimzi)"
    echo "  - Kafka cluster 'dev-cluster' (namespace: kafka)"
    echo "  - Apicurio Registry operator  (namespace: apicurio-registry)"
    echo "  - Apicurio Registry instance  (namespace: apicurio-registry, storage: in-memory)"
    echo "  - StreamsHub Console operator (namespace: streamshub-console)"
    echo "  - StreamsHub Console instance (namespace: streamshub-console)"
    if [ "${OVERLAY}" = "metrics" ]; then
        echo "  - Prometheus operator         (namespace: monitoring)"
        echo "  - Prometheus instance          (namespace: monitoring)"
        echo "  - Kafka metrics (PodMonitors) (namespace: monitoring)"
    fi
    echo ""
    echo "Verify with:"
    echo "  kubectl get deployment -n strimzi strimzi-cluster-operator"
    echo "  kubectl get kafka -n kafka"
    echo "  kubectl get deployment -n apicurio-registry apicurio-registry-operator"
    echo "  kubectl get apicurioregistry3 -n apicurio-registry"
    echo "  kubectl get deployment -n streamshub-console console-operator"
    echo "  kubectl get console -n streamshub-console"
    if [ "${OVERLAY}" = "metrics" ]; then
        echo "  kubectl get prometheus -n monitoring"
        echo "  kubectl get podmonitor -n monitoring"
    fi
}

main
