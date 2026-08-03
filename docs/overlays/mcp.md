+++
title = 'MCP'
weight = 2
cpu_total = '3 CPU cores'
memory_total = '5 GiB'
+++

The MCP overlay extends the core stack with the StreamsHub MCP server, giving AI assistants (Claude Code, VS Code Copilot, etc.) read-only access to the Strimzi-managed Kafka cluster.

## Quick-Start Install

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=mcp bash
```

## Manual Install

If you prefer step-by-step control, the MCP overlay uses `overlays/mcp` instead of `overlays/core`:

```shell
# Phase 1 — Operators and CRDs
kubectl apply --server-side --force-conflicts -k 'https://github.com/streamshub/developer-quickstart//overlays/mcp/base?ref=main'

# Optionally, wait for the operators to be ready
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s

# Phase 2 — Operands and MCP server
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/mcp/stack?ref=main'
```

## Uninstall

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=mcp bash
```

## What Gets Added

On top of the components provided by the core overlay, the MCP overlay adds:

| Component              | Namespace        | Description                                              |
|------------------------|------------------|----------------------------------------------------------|
| StreamsHub MCP server  | `streamshub-mcp` | MCP server for AI-assisted Kafka cluster management      |
| Sensitive Role         | `kafka`          | Grants access to TLS certificates and pod metrics        |

The MCP server provides read-only tools for inspecting and troubleshooting Strimzi-managed Kafka clusters via the [Model Context Protocol](https://modelcontextprotocol.io/).

## Resource Requirements

The MCP overlay requires at least {{< param cpu_total >}} and {{< param memory_total >}} of allocatable cluster resources.
This includes the resources for the [core](core.md) stack plus the MCP server listed above.

## Accessing the MCP Server

Port-forward to the MCP server:

```shell
kubectl port-forward -n streamshub-mcp svc/streamshub-strimzi-mcp 8085:8080
```

The MCP endpoint is available at `http://localhost:8085/mcp`.

## Verify the Installation

Confirm the MCP server is running:

```shell
kubectl get deployment -n streamshub-mcp streamshub-strimzi-mcp
```

The deployment should show `1/1` ready replicas. Check the health endpoint:

```shell
kubectl port-forward -n streamshub-mcp svc/streamshub-strimzi-mcp 8085:8080 &
curl -s http://localhost:8085/q/health/ready
```

## Troubleshooting

### MCP Server Not Starting

If the MCP server deployment is not becoming ready:

```shell
# Check pod status
kubectl get pods -n streamshub-mcp

# Check logs
kubectl logs -n streamshub-mcp deployment/streamshub-strimzi-mcp

# Verify Strimzi CRDs are installed
kubectl get crd kafkas.kafka.strimzi.io
```

**Common causes:**

- Strimzi operator not installed — the MCP server requires Strimzi CRDs to exist
- Insufficient cluster resources — check node resource availability
- Image pull errors — verify the cluster can pull from `quay.io`
