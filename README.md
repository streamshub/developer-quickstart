# StreamsHub Developer Quick-Start

A Kustomize-based repository for deploying the StreamsHub event-streaming stack on a local or development Kubernetes cluster using only `kubectl`.

> **Note:** This is a **development-only** configuration. Resource limits, security settings and storage configurations are **not** suitable for production use.

## What Gets Deployed

| Component | Namespace | Description |
|-----------|-----------|-------------|
| Strimzi Kafka Operator | `strimzi` | Manages Kafka clusters via CRDs |
| Kafka cluster (`dev-cluster`) | `kafka` | Single-node Kafka for development |
| Apicurio Registry Operator | `apicurio-registry` | Manages schema registry instances |
| Apicurio Registry instance | `apicurio-registry` | In-memory schema registry |
| StreamsHub Console Operator | `streamshub-console` | Manages console instances |
| StreamsHub Console instance | `streamshub-console` | Web UI for Kafka management |

## Prerequisites

- `kubectl` v1.27 or later (for Kustomize v5.0 `labels` transformer support)
- A running Kubernetes cluster (minikube, KIND, etc.)
- An Ingress controller for StreamsHub Console access (e.g. `minikube addons enable ingress`)

## Quick-Start Install

Deploy the entire stack with a single command:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | bash
```

This script installs operators, waits for them to become ready, then deploys the operands.

### Configuration

The install script accepts the following environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `REPO` | `streamshub/developer-quickstart` | GitHub repository path |
| `REF` | `main` | Git ref, branch, or tag |
| `TIMEOUT` | `120s` | `kubectl wait` timeout |

Example with a pinned version:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | REF=v1.0.0 bash
```

## Manual Install

If you prefer to control each step, the stack is installed in two phases:

### Phase 1 — Operators and CRDs

```shell
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//base?ref=main'
```

Wait for the operators to become ready:

```shell
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s
```

### Phase 2 — Operands

```shell
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//stack?ref=main'
```

## Accessing the Console

### Minikube

When using minikube, (if you didn't eanble it when you created the minikube cluster) enable the ingress addon and run `minikube tunnel`:

```bash
minikube addons enable ingress
minikube tunnel
```

Then use port-forwarding to access the console:

```bash
kubectl port-forward -n streamshub-console svc/streamshub-console-console-service 8080:80
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

## Teardown

### Using the Uninstall Script

The uninstall script handles safe teardown with shared-cluster safety checks:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | bash
```

The script:
1. Deletes operand custom resources and waits for finalizers to complete
2. Checks each operator group for non-quick-start CRs on the cluster
3. Fully removes operator groups with no shared CRDs
4. For shared operator groups, removes only the operator deployment (retaining CRDs)
5. Reports any retained groups and remaining resources

### Manual Teardown

**Phase 1 — Delete operands:**

```shell
kubectl delete -k 'https://github.com/streamshub/developer-quickstart//stack?ref=main'
```

Wait for all custom resources to be fully removed before proceeding.

**Phase 2 — Delete operators and CRDs:**

> **Warning:** On shared clusters, deleting CRDs will cascade-delete ALL custom resources of that type cluster-wide. Check for non-quick-start resources first:
> ```shell
> kubectl get kafkas -A --selector='!app.kubernetes.io/part-of=streamshub-developer-quickstart'
> ```

```shell
kubectl delete -k 'https://github.com/streamshub/developer-quickstart//base?ref=main'
```

### Finding Quick-Start Resources

All resources carry the label `app.kubernetes.io/part-of=streamshub-developer-quickstart`:

```shell
kubectl get all -A -l app.kubernetes.io/part-of=streamshub-developer-quickstart
kubectl get crds,clusterroles,clusterrolebindings -l app.kubernetes.io/part-of=streamshub-developer-quickstart
```

## Updating Component Versions

Use the `update-version.sh` script to update component versions:

```shell
# List available versions
./update-version.sh --list strimzi

# Preview changes
./update-version.sh --dry-run strimzi 0.52.0

# Check if a release exists
./update-version.sh --check apicurio-registry 3.2.0

# Update a component
./update-version.sh strimzi 0.52.0
```

Supported components: `strimzi`, `apicurio-registry`, `streamshub-console`

## Repository Structure

```
base/                               # Phase 1: Operators & CRDs
├── kustomization.yaml              # Composes all operator sub-components
├── strimzi-operator/               # Strimzi Kafka Operator
├── apicurio-registry-operator/     # Apicurio Registry Operator
└── streamshub-console-operator/    # StreamsHub Console Operator

stack/                              # Phase 2: Operands (Custom Resources)
├── kustomization.yaml              # Composes all operand sub-components
├── kafka/                          # Single-node Kafka cluster
├── apicurio-registry/              # In-memory registry instance
└── streamshub-console/             # Console instance

overlays/                           # Future: variant configurations
```

## Current Component Versions

| Component | Version |
|-----------|---------|
| Strimzi | 0.51.0 |
| Apicurio Registry | 3.1.7 |
| StreamsHub Console | 0.11.0 |
