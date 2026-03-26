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

> **Optional:** The [metrics overlay](#install-with-metrics) adds Prometheus Operator, a Prometheus instance, and Kafka metrics collection via PodMonitors.

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
| `OVERLAY` | *(empty)* | Overlay to apply (e.g. `metrics`) |
| `TIMEOUT` | `120s` | `kubectl wait` timeout |

Example with a pinned version:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | REF=v1.0.0 bash
```

### Install with Metrics

Deploy the stack with Prometheus metrics collection:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=metrics bash
```

This adds the Prometheus Operator and a Prometheus instance (namespace: `monitoring`), enables Kafka metrics via [Strimzi Metrics Reporter](https://strimzi.io/docs/operators/latest/deploying#proc-metrics-kafka-str), and wires Console to display metrics.

## Manual Install

If you prefer to control each step, the stack is installed in two phases:

### Phase 1 — Operators and CRDs

```shell
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/core/base?ref=main'
```

Wait for the operators to become ready:

```shell
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s
```

### Phase 2 — Operands

```shell
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/core/stack?ref=main'
```

### Manual Install with Metrics

To include the metrics overlay, use the `overlays/metrics` paths instead of `overlays/core`.

```shell
# Phase 1
kubectl create -k 'https://github.com/streamshub/developer-quickstart//overlays/metrics/base?ref=main'
kubectl wait --for=condition=Available deployment/prometheus-operator -n monitoring --timeout=120s
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s

# Phase 2
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/metrics/stack?ref=main'
```

## Accessing the Console

### Minikube

When using minikube, (if you didn't enable it when you created the minikube cluster) enable the ingress addon and run `minikube tunnel`:

```bash
minikube addons enable ingress
minikube tunnel
```

Then use port-forwarding to access the console:

```bash
kubectl port-forward -n streamshub-console svc/streamshub-console-console-service 8080:80
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

### Kind

When using Kind, create the cluster with ingress-ready port mappings:

```bash
cat <<EOF | kind create cluster --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
EOF
```

Then deploy an ingress controller (e.g. ingress-nginx):

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/kind/deploy.yaml
kubectl wait --namespace ingress-nginx \
  --for=condition=Ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s
```

Use port-forwarding to access the console:

```bash
kubectl port-forward -n streamshub-console svc/streamshub-console-console-service 8080:80
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

## Teardown

### Using the Uninstall Script

The uninstall script handles safe teardown with shared-cluster safety checks:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | bash

# If installed with the metrics overlay:
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=metrics bash
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
kubectl delete -k 'https://github.com/streamshub/developer-quickstart//overlays/core/stack?ref=main'
```

Wait for all custom resources to be fully removed before proceeding.

**Phase 2 — Delete operators and CRDs:**

> **Warning:** On shared clusters, deleting CRDs will cascade-delete ALL custom resources of that type cluster-wide. Check for non-quick-start resources first:
> ```shell
> kubectl get kafkas -A --selector='!app.kubernetes.io/part-of=streamshub-developer-quickstart'
> ```

```shell
kubectl delete -k 'https://github.com/streamshub/developer-quickstart//overlays/core/base?ref=main'
```

For the metrics overlay, use `overlays/metrics/base` and `overlays/metrics/stack` instead.

### Finding Quick-Start Resources

All resources carry the label `app.kubernetes.io/part-of=streamshub-developer-quickstart`:

```shell
kubectl get all -A -l app.kubernetes.io/part-of=streamshub-developer-quickstart
kubectl get crds,clusterroles,clusterrolebindings -l app.kubernetes.io/part-of=streamshub-developer-quickstart
```

## Development

### Updating Component Versions

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

Supported components: `strimzi`, `apicurio-registry`, `streamshub-console`, `prometheus-operator`

### Testing scripts locally

When developing changes to the kustomization files, use the `LOCAL_DIR` environment
variable to point the install and uninstall scripts at your local checkout instead
of the remote GitHub repository:

```shell
# Install from local repo
LOCAL_DIR=. ./install.sh

# Uninstall from local repo
LOCAL_DIR=. ./uninstall.sh
```

When `LOCAL_DIR` is set, `REPO` and `REF` are ignored — the scripts resolve
kustomization paths relative to the given directory.

You can also provide an absolute path:

```shell
LOCAL_DIR=/home/user/repos/developer-quickstart ./install.sh
```

## Testing

### CI Smoke Tests

Pull requests and pushes to `main` trigger integration smoke tests via GitHub Actions. The CI pipeline:

1. Computes a test matrix — `ComputeTestMatrix.java` analyses overlay component dependencies to identify "leaf" overlays (those not fully covered by a larger overlay). Only leaf overlays are tested, avoiding redundant runs.
2. Runs each leaf overlay on every configured platform (minikube and kind by default):
   - Installs the stack using `install.sh`
   - Verifies all deployments are ready and custom resources reach their expected conditions
   - Uninstalls using `uninstall.sh` and verifies all resources are removed
3. Collects diagnostics on failure — CR status, events, pod listings, and logs

### Test Configuration

Per-overlay test settings are defined in `.github/config/test-matrix.yaml`. This is the central place for test-specific configuration that doesn't belong in the overlay definitions themselves.

```yaml
overlays:
  metrics:
    condition-overrides: "monitoring.coreos.com=Available"
```

| Field | Description |
|-------|-------------|
| `condition-overrides` | Space-separated `apiGroup=Condition` pairs. Custom resources belonging to the given API group will be checked for the specified condition instead of the default `Ready`. |

### Running Tests Locally

The test scripts are [JBang](https://www.jbang.dev/) scripts located in `.github/scripts/`:

| Script | Purpose |
|--------|---------|
| `ComputeTestMatrix.java` | Computes the CI test matrix from overlay dependencies |
| `VerifyInstall.java` | Verifies deployments and custom resources are ready |
| `VerifyUninstall.java` | Verifies all quickstart resources are removed |
| `Debug.java` | Dumps diagnostic info (CR status, events, pod logs) |
| `ComputeTestMatrixTest.java` | Unit tests for the matrix computation logic |

To run the unit tests:

```shell
jbang .github/scripts/ComputeTestMatrixTest.java
```

To run the verification scripts against a live cluster:

```shell
# Verify install (requires a running cluster with the stack deployed)
OVERLAY=core jbang .github/scripts/VerifyInstall.java

# Verify uninstall (after running uninstall.sh)
jbang .github/scripts/VerifyUninstall.java

# Dump diagnostics
OVERLAY=core jbang .github/scripts/Debug.java
```

The scripts accept configuration via environment variables:

| Variable | Used by | Default | Description |
|----------|---------|---------|-------------|
| `OVERLAY` | VerifyInstall, Debug | `core` | Overlay name to verify |
| `TIMEOUT` | VerifyInstall | `600s` | Wait timeout (supports `s`, `m`, `h` suffixes) |
| `CONDITION_OVERRIDES` | VerifyInstall | *(empty)* | Space-separated `apiGroup=Condition` pairs |
| `PLATFORMS` | ComputeTestMatrix | `minikube kind` | Space-separated list of target platforms |
| `LOG_TAIL_LINES` | Debug | `30` | Number of log lines to tail per pod |

## Repository Structure

```
components/                         # Reusable Kustomize components
├── core/                           # Core stack component
│   ├── base/                       # Operators & CRDs
│   │   ├── strimzi-operator/       # Strimzi Kafka Operator
│   │   ├── apicurio-registry-operator/  # Apicurio Registry Operator
│   │   └── streamshub-console-operator/ # StreamsHub Console Operator
│   └── stack/                      # Operands (Custom Resources)
│       ├── kafka/                  # Single-node Kafka cluster
│       ├── apicurio-registry/      # In-memory registry instance
│       └── streamshub-console/     # Console instance
└── metrics/                        # Prometheus metrics component
    ├── base/                       # Prometheus Operator
    └── stack/                      # Prometheus instance, PodMonitors, patches

overlays/                           # Deployable configurations
├── core/                           # Default install (core only)
│   ├── base/                       # Phase 1: Operators & CRDs
│   └── stack/                      # Phase 2: Operands
└── metrics/                        # Core + Prometheus metrics
    ├── base/                       # Phase 1: Operators & CRDs + Prometheus Operator
    └── stack/                      # Phase 2: Operands + Prometheus instance & monitors
```
