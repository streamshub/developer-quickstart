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
# Environment variables:
#   LOCAL_DIR - Use local directory instead of GitHub (e.g. LOCAL_DIR=.)
#   REPO      - GitHub repo path (default: streamshub/developer-quickstart)
#   REF       - Git ref/branch/tag (default: main)
#   TIMEOUT   - kubectl wait timeout (default: 120s)
#

set -euo pipefail

# Defaults (overridable via environment variables)
LOCAL_DIR="${LOCAL_DIR:-}"
REPO="${REPO:-streamshub/developer-quickstart}"
REF="${REF:-main}"
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
    echo ""
    info "Installing StreamsHub developer quick-start stack"
    if [ -n "${LOCAL_DIR}" ]; then
        info "Dir: ${LOCAL_DIR} | Timeout: ${TIMEOUT}"
    else
        info "Repo: ${REPO} | Ref: ${REF} | Timeout: ${TIMEOUT}"
    fi
    echo ""

    # --- Prerequisites ---
    check_prerequisites
    echo ""

    # --- Step 1: Install operators (base layer) ---
    local base_url
    base_url=$(kustomize_url "base")
    info "Step 1/5: Installing operators and CRDs..."
    info "Applying: ${base_url}"
    kubectl apply -k "${base_url}"
    echo ""

    # --- Step 2: Wait for Strimzi operator ---
    info "Step 2/5: Waiting for strimzi-cluster-operator to be ready (timeout: ${TIMEOUT})..."
    kubectl wait --for=condition=Available deployment/strimzi-cluster-operator \
        -n strimzi --timeout="${TIMEOUT}"
    info "Strimzi operator is ready"
    echo ""

    # --- Step 3: Wait for Apicurio Registry operator ---
    info "Step 3/5: Waiting for apicurio-registry-operator to be ready (timeout: ${TIMEOUT})..."
    kubectl wait --for=condition=Available deployment/apicurio-registry-operator \
        -n apicurio-registry --timeout="${TIMEOUT}"
    info "Apicurio Registry operator is ready"
    echo ""

    # --- Step 4: Wait for StreamsHub Console operator ---
    info "Step 4/5: Waiting for console-operator to be ready (timeout: ${TIMEOUT})..."
    kubectl wait --for=condition=Available deployment/streamshub-console-operator \
        -n streamshub-console --timeout="${TIMEOUT}"
    info "StreamsHub Console operator is ready"
    echo ""

    # --- Step 5: Install operands (stack layer) ---
    local stack_url
    stack_url=$(kustomize_url "stack")
    info "Step 5/5: Installing operands (Kafka cluster, Registry instance, Console)..."
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
    echo ""
    echo "Verify with:"
    echo "  kubectl get deployment -n strimzi strimzi-cluster-operator"
    echo "  kubectl get kafka -n kafka"
    echo "  kubectl get deployment -n apicurio-registry apicurio-registry-operator"
    echo "  kubectl get apicurioregistry3 -n apicurio-registry"
    echo "  kubectl get deployment -n streamshub-console console-operator"
    echo "  kubectl get console -n streamshub-console"
}

main
