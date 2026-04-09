+++
title = 'Architecture'
weight = 6
+++

# Architecture

## Two-Phase Deployment

The event stack is deployed in two sequential phases:

**Phase 1 - The Base - Operators and CRDs:** Deploys operator Deployments, RBAC resources, and Custom Resource Definitions. 
The install script waits for each operator to become ready before proceeding.

**Phase 2 - The Stack - Operands:** Deploys the actual workloads as Custom Resources (Kafka, ApicurioRegistry3, Console). 
Operators must be running to process these resources.

This separation exists for three reasons:

1. **CRD registration** — Kubernetes must register CRDs before it can accept custom resources of that type
2. **Operator readiness** — operators must be running to reconcile their custom resources
3. **Safe teardown** — during uninstall, operands are deleted first while operators are still alive to process finalizers

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
