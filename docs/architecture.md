+++
title = 'Architecture'
weight = 6
+++

## Two-Phase Deployment

The event stack is deployed in two sequential phases:

**Phase 1 - The Base - Operators and CRDs:** Deploys operator Deployments, RBAC resources, and Custom Resource Definitions. 
The install script waits for each operator to become ready before proceeding.

**Phase 2 - The Stack - Operands:** Deploys the actual workloads as Custom Resources (Kafka, ApicurioRegistry3, Console). 
Operators must be running to process these resources.

This separation exists for three reasons:

1. CRD registration — Kubernetes must register CRDs before it can accept custom resources of that type
2. Operator readiness — operators must be running to reconcile their custom resources
3. Safe teardown — during uninstall, operands are deleted first while operators are still alive to process finalizers

The install script uses `kubectl apply --server-side` for Phase 1 to handle large CRDs (such as those from the Prometheus Operator) that exceed the annotation size limit used by client-side apply.

## Kustomize Structure

The repository uses a component-based Kustomize architecture:

```
components/                           # Reusable Kustomize components
├── core/
│   ├── base/                         # Component: operators & CRDs
│   └── stack/                        # Component: operands
└── metrics/                          # Optional metrics component
    ├── base/
    └── stack/
overlays/                             # Deployable configurations
├── core/                             # Default (no metrics)
│   ├── base/                         # Phase 1: components/core/base
│   └── stack/                        # Phase 2: components/core/stack
└── metrics/                          # Core + Prometheus
    ├── base/                         # Phase 1: core/base + metrics/base
    └── stack/                        # Phase 2: core/stack + metrics/stack
```

**Components** are reusable building blocks (Kustomize `Component` kind). 
They define operators, operands, and patches but are not directly deployable.

**Overlays** compose components into deployable configurations. 
Each overlay has a `base` (Phase 1) and `stack` (Phase 2) directory. 
The `metrics` overlay includes everything from `core` plus the Prometheus components.

## Resource Labeling

Every resource deployed by the quick-start carries the label:

```yaml
app.kubernetes.io/part-of: streamshub-developer-quickstart
```

This label is applied by the Kustomize `labels` transformer and serves two purposes:

- Resource discovery — find all quick-start resources with a single label selector
- Shared-cluster safety — the uninstall script uses label selectors to distinguish quick-start resources from user-created ones, preventing accidental deletion of CRDs that other deployments depend on

## Namespace Isolation

Each component runs in its own namespace:

| Namespace            | Contents                                           |
|----------------------|----------------------------------------------------|
| `strimzi`            | Strimzi Kafka Operator                             |
| `kafka`              | Kafka cluster (`dev-cluster`)                      |
| `apicurio-registry`  | Registry Operator and instance                     |
| `streamshub-console` | Console Operator and instance                      |
| `monitoring`         | Prometheus Operator and instance (metrics overlay) |

## Updating Component Versions

Use the `update-version.sh` script to manage operator versions:

```shell
# List available versions for a component
./update-version.sh --list strimzi

# Preview changes without modifying files
./update-version.sh --dry-run strimzi 0.52.0

# Check if a specific release exists
./update-version.sh --check apicurio-registry 3.2.0

# Apply the update
./update-version.sh strimzi 0.52.0
```

Supported components: `strimzi`, `apicurio-registry`, `streamshub-console`, `prometheus-operator`

The script updates the remote resource URLs in the relevant `kustomization.yaml` files to point to the new version's release artifacts.

## Scaling the Kafka Cluster

The default deployment uses the upstream Strimzi [`kafka-single-node.yaml`](https://github.com/strimzi/strimzi-kafka-operator/blob/0.51.0/examples/kafka/kafka-single-node.yaml) example with a single broker.
To scale to 3 replicas, edit `components/core/stack/kafka/kustomization.yaml` and change the resource URL to use Strimzi's [`kafka-with-dual-role-nodes.yaml`](https://github.com/strimzi/strimzi-kafka-operator/blob/0.51.0/examples/kafka/kafka-with-dual-role-nodes.yaml) example instead:

```yaml
resources:
  - https://raw.githubusercontent.com/strimzi/strimzi-kafka-operator/refs/tags/0.51.0/examples/kafka/kafka-with-dual-role-nodes.yaml
  - namespace.yaml
```

This example is structurally identical to the single-node version (same KafkaNodePool name, listeners, and storage) but configures 3 replicas with the following replication settings:

| Property | Value | Notes |
|----------|-------|-------|
| `offsets.topic.replication.factor` | 3 | |
| `transaction.state.log.replication.factor` | 3 | |
| `transaction.state.log.min.isr` | 2 | replicas − 1 |
| `default.replication.factor` | 3 | |
| `min.insync.replicas` | 2 | replicas − 1 |

All existing patches (cluster rename, resource limits, entity operator config) apply without changes.

For replica counts other than 1 or 3, start from either example and add patches for `spec.replicas` on the KafkaNodePool and the replication config values on the Kafka CR.

**Considerations:**

- **ISR values** should be `replicas − 1`, not equal to `replicas`. Setting `min.insync.replicas` equal to the replica count means a single broker failure blocks all writes
- **KRaft quorum** — the cluster uses KRaft (no ZooKeeper) with dual-role nodes (controller + broker). An odd number of replicas (3 or 5) is recommended for controller leader election
- **Resource usage** scales linearly — 3 replicas requires 3× the CPU and memory of a single node. You may need to increase cluster resources (e.g. `minikube start --cpus=8 --memory=12g`)
- **Local changes** require `LOCAL_DIR=.` when using the install script, which otherwise fetches manifests from GitHub. See [Install from a Local Checkout](installation.md#install-from-a-local-checkout)
