# StreamsHub Developer Quick-Start

A Kustomize-based repository for deploying the StreamsHub event-streaming stack on a local or development Kubernetes cluster using only `kubectl`.

> **Note:** This is a **development-only** configuration. Resource limits, security settings and storage configurations are **not** suitable for production use.

## What Gets Deployed

The stack deploys Strimzi Kafka, Apicurio Registry, and StreamsHub Console operators along with their operand instances. 
Optional [overlays](docs/overlays/_index.md) add components such as Prometheus metrics.

See [What Gets Deployed](docs/_index.md#what-gets-deployed) for the full component breakdown.

## Prerequisites

- `kubectl` v1.27 or later
- A running Kubernetes cluster (minikube, KIND, etc.) with an ingress controller 

See [Prerequisites](docs/prerequisites.md) for full requirements and cluster setup instructions.

## Quick-Start Install

Deploy the entire stack with a single command:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | bash
```

This script installs operators, waits for them to become ready, then deploys the operands.

To install with an overlay (e.g. metrics):

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=metrics bash
```

See [Installation](docs/installation.md) for configuration options, manual install steps, and installing from a local checkout.

## Accessing Services

After installation, use port-forwarding to access the Console:

```shell
kubectl port-forward -n streamshub-console svc/streamshub-console-console-service 8090:80
```

Open [http://localhost:8090](http://localhost:8090) in your browser.

See [Accessing Services](docs/accessing-services.md) for Kafka, Apicurio Registry access, and platform-specific instructions.

## Teardown

Remove the quick-start stack:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | bash
```

See [Uninstallation](docs/uninstallation.md) for metrics overlay uninstall, manual teardown, and shared-cluster safety details.

## Documentation

Full documentation is available in the [`docs/`](docs/) directory:

- [Prerequisites](docs/prerequisites.md) — requirements and cluster setup
- [Installation](docs/installation.md) — all install methods, configuration, and local development
- [Accessing Services](docs/accessing-services.md) — Console, Kafka, and Registry access
- [Architecture](docs/architecture.md) — deployment model, Kustomize structure, versioning
- [Overlays](docs/overlays/_index.md) — optional extensions (metrics, etc.)
- [Uninstallation](docs/uninstallation.md) — safe teardown on any cluster
- [Troubleshooting](docs/troubleshooting.md) — common issues and diagnostics

## Development

For development workflows including updating component versions and testing scripts locally, see:
- [Updating Component Versions](docs/architecture.md#updating-component-versions)
- [Install from a Local Checkout](docs/installation.md#install-from-a-local-checkout)

### Previewing Documentation Locally

The `docs/` directory contains the project documentation. To preview it locally with Hugo:

```shell
./docs/preview.sh
```

This starts a local server at [http://localhost:1313/docs/](http://localhost:1313/docs/) with live-reload. 
Press `Ctrl+C` to stop.

To generate static HTML instead:

```shell
./docs/preview.sh build
```

The output is written to `.docs-preview/public/`.

**Requirements:** `hugo`, `git`, and `go` must be installed. 
The script handles theme fetching and site configuration automatically.

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
