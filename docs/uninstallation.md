+++
title = 'Uninstallation'
weight = 5
+++

# Uninstallation

## Using the Uninstall Script

The uninstall script provides safe teardown with shared-cluster awareness:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | bash
```

If you installed with the metrics overlay:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=metrics bash
```

### Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `REPO` | `streamshub/developer-quickstart` | GitHub repository path |
| `REF` | `main` | Git ref, branch, or tag |
| `OVERLAY` | *(empty)* | Overlay to uninstall (e.g. `metrics`) |
| `TIMEOUT` | `120s` | kubectl wait/poll timeout |
| `LOCAL_DIR` | *(empty)* | Use a local directory instead of GitHub |

### What the Script Does

The uninstall follows a 4-phase process designed to be safe on shared clusters:

**Phase 1 — Interactive CR cleanup** (interactive mode only)

Scans for Kafka, Registry, and Console resources that were *not* created by the quick-start (i.e., resources without the `app.kubernetes.io/part-of=streamshub-developer-quickstart` label). If found, you're prompted to delete them so their finalizers can be processed while operators are still running.

**Phase 2 — Delete operands**

Removes the quick-start custom resources (Kafka, Registry, Console instances) and waits for finalizers to complete.

**Phase 3 — Shared-cluster safety checks**

For each operator group (Strimzi, Apicurio, Console, Prometheus), the script checks whether non-quick-start custom resources exist on the cluster. This determines whether CRDs are shared with other deployments.

**Phase 4 — Selective operator removal**

- **Not shared** — the entire operator group is removed, including CRDs
- **Shared** — only the operator deployment is removed; CRDs are retained so other deployments continue to function

The script reports which operator groups were retained and why.

## Manual Teardown

> **Warning:** On shared clusters, deleting CRDs will cascade-delete **all** custom resources of that type cluster-wide. Always check for non-quick-start resources first.

### Phase 1 — Delete Operands

```shell
kubectl delete -k 'https://github.com/streamshub/developer-quickstart//overlays/core/stack?ref=main'
```

Wait for all custom resources to be fully removed before continuing.

### Phase 2 — Delete Operators and CRDs

First, check for shared resources:

```shell
kubectl get kafkas -A --selector='!app.kubernetes.io/part-of=streamshub-developer-quickstart'
kubectl get apicurioregistry3 -A --selector='!app.kubernetes.io/part-of=streamshub-developer-quickstart'
kubectl get console.console.streamshub.github.com -A --selector='!app.kubernetes.io/part-of=streamshub-developer-quickstart'
```

If no shared resources exist, delete the operators:

```shell
kubectl delete -k 'https://github.com/streamshub/developer-quickstart//overlays/core/base?ref=main'
```

For the metrics overlay, use `overlays/metrics/base` and `overlays/metrics/stack` instead of `overlays/core`.

## Finding Quick-Start Resources

All resources deployed by the quick-start carry the label `app.kubernetes.io/part-of=streamshub-developer-quickstart`:

```shell
# List all namespaced resources
kubectl get all -A -l app.kubernetes.io/part-of=streamshub-developer-quickstart

# List cluster-scoped resources
kubectl get crds,clusterroles,clusterrolebindings -l app.kubernetes.io/part-of=streamshub-developer-quickstart
```
