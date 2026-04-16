+++
title = 'Installation'
weight = 2
+++

## Quick-Start Install

Deploy the entire core stack with a single command:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | bash
```

The script:

1. Checks prerequisites (kubectl, IngressClass)
2. Installs operators and CRDs (Phase 1)
3. Waits for each operator to become ready
4. Deploys Kafka, Registry, and Console instances (Phase 2)

### Install with an Overlay

Overlays extend the core stack with optional components. To install with an overlay, set the `OVERLAY` variable:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=<name> bash
```

See [Overlays](overlays/_index.md) for the list of available overlays and what each one adds.

### Configuration

The install script accepts the following environment variables:

| Variable    | Default                           | Description                                                                 |
|-------------|-----------------------------------|-----------------------------------------------------------------------------|
| `REPO`      | `streamshub/developer-quickstart` | GitHub repository path                                                      |
| `REF`       | `main`                            | Git ref, branch, or tag                                                     |
| `OVERLAY`   | *(empty)*                         | Overlay to apply (e.g. `metrics`)                                           |
| `TIMEOUT`   | `120s`                            | `kubectl wait` timeout (supports `s`, `m`, `h` suffixes)                    |
| `LOCAL_DIR` | *(empty)*                         | Use a local directory as the source of install files instead of GitHub URLs |

**Examples:**

```shell
# Install a specific version
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | REF=v1.0.0 bash

# Increase timeout for slower clusters
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | TIMEOUT=300s bash
```

## Manual Install

If you prefer step-by-step control, the stack is installed in two phases.

### Phase 1 — Operators and CRDs

```shell
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/core/base?ref=main'
```

Optionally, you can wait for the operators to become ready using the commands below:

```shell
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s
```

### Phase 2 — Operands

```shell
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/core/stack?ref=main'
```

### Manual Install with an Overlay

For overlays, replace `overlays/core` with `overlays/<name>` in the commands above. 
Each overlay may add additional operators that need to be waited on during Phase 1. 
See the specific overlay page under [Overlays](overlays/_index.md) for the full manual install commands.

## Install from a Local Checkout

When developing or testing changes to the kustomization files, point the scripts at a local directory:

```shell
# Clone and install from local
git clone https://github.com/streamshub/developer-quickstart.git
cd developer-quickstart
LOCAL_DIR=. ./install.sh
```

When `LOCAL_DIR` is set, `REPO` and `REF` are ignored — all kustomization paths resolve relative to the given directory. 
You can also pass an absolute path:

```shell
LOCAL_DIR=/home/user/repos/developer-quickstart ./install.sh
```

## Verify the Installation

After installation, confirm all components are running:

```shell
# Check operators
kubectl get deployment -n strimzi strimzi-cluster-operator
kubectl get deployment -n apicurio-registry apicurio-registry-operator
kubectl get deployment -n streamshub-console streamshub-console-operator

# Check operands
kubectl get kafka -n kafka
kubectl get apicurioregistry3 -n apicurio-registry
kubectl get console -n streamshub-console

```

All operator deployments should show `READY 1/1`. Custom resources should reach `Ready` status.

If you installed with an overlay, check its page under [Overlays](overlays/_index.md) for additional verification steps.
