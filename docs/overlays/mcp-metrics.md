+++
title = 'MCP + Metrics'
weight = 3
cpu_total = '3 CPU cores'
memory_total = '5.5 GiB'
+++

The MCP + Metrics overlay combines the core stack with both Prometheus monitoring and the StreamsHub MCP server. 
The MCP server is configured to use Prometheus as its centralized metrics provider.

## Quick-Start Install

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=mcp-metrics bash
```

## Manual Install

If you prefer step-by-step control, the MCP + Metrics overlay uses `overlays/mcp-metrics`:

```shell
# Phase 1 — Operators and CRDs (includes Prometheus Operator)
kubectl apply --server-side --force-conflicts -k 'https://github.com/streamshub/developer-quickstart//overlays/mcp-metrics/base?ref=main'

# Optionally, wait for the operators to be ready
kubectl wait --for=condition=Available deployment/prometheus-operator -n monitoring --timeout=120s
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s

# Phase 2 — Operands, MCP server, Prometheus instance and monitors
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/mcp-metrics/stack?ref=main'
```

## Uninstall

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=mcp-metrics bash
```

## What Gets Added

On top of the components provided by the core overlay, the MCP + Metrics overlay adds:

| Component              | Namespace        | Description                                              |
|------------------------|------------------|----------------------------------------------------------|
| Prometheus Operator    | `monitoring`     | Manages Prometheus instances and monitors                |
| Prometheus instance    | `monitoring`     | Collects and stores metrics (400Mi memory, 1 replica)    |
| PodMonitors            | `monitoring`     | Scrape targets for Kafka brokers and Strimzi operators   |
| ServiceMonitor         | `monitoring`     | Scrape target for StreamsHub Console operator            |
| StreamsHub MCP server  | `streamshub-mcp` | MCP server configured with Prometheus metrics provider   |
| MCP PodMonitor         | `monitoring`     | Scrape target for MCP server metrics                     |
| Sensitive Role         | `kafka`          | Grants access to TLS certificates and pod metrics        |

The overlay patches existing resources:

- Kafka — enables the [Strimzi Metrics Reporter](https://strimzi.io/docs/operators/latest/deploying#proc-metrics-kafka-str) on the `dev-cluster`
- Console — adds Prometheus as a metrics data source so the Console UI displays Kafka metrics
- MCP server — configured to use Prometheus as centralized metrics provider instead of direct pod scraping

## Resource Requirements

The MCP + Metrics overlay requires at least {{< param cpu_total >}} and {{< param memory_total >}} of allocatable cluster resources.
This includes the resources for the [core](core.md) stack plus the additional components listed above.

## Accessing the MCP Server

Port-forward to the MCP server:

```shell
kubectl port-forward -n streamshub-mcp svc/streamshub-strimzi-mcp 8085:8080
```

The MCP endpoint is available at `http://localhost:8085/mcp`.

## Accessing Prometheus

Port-forward to the Prometheus UI:

```shell
kubectl port-forward -n monitoring svc/prometheus-operated 9090:9090
```

Open [http://localhost:9090](http://localhost:9090) and navigate to **Status > Targets** to verify all scrape targets are up, including the MCP server.

## Verify the Installation

Confirm all components are running:

```shell
kubectl get deployment -n streamshub-mcp streamshub-strimzi-mcp
kubectl get prometheus -n monitoring
kubectl get podmonitor -n monitoring
```

The MCP server deployment should show `1/1` ready replicas. Prometheus should reach `Available` status.

## Troubleshooting

### MCP Server Not Starting

See the [MCP overlay troubleshooting](mcp.md#troubleshooting) section.

### Metrics Not Appearing

See the [Metrics overlay troubleshooting](metrics.md#metrics-not-appearing) section.
