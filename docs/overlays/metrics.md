+++
title = 'Metrics'
weight = 1
+++

# Metrics Overlay

The metrics overlay extends the core stack with Prometheus-based monitoring.

## Quick-Start Install

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=metrics bash
```

## Manual Install

If you prefer step-by-step control, the metrics overlay uses `overlays/metrics` instead of `overlays/core`:

```shell
# Phase 1 — Operators and CRDs (includes Prometheus Operator)
kubectl create -k 'https://github.com/streamshub/developer-quickstart//overlays/metrics/base?ref=main'

# Optionally, wait for the operators to be ready
kubectl wait --for=condition=Available deployment/prometheus-operator -n monitoring --timeout=120s
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s

# Phase 2 — Operands (includes Prometheus instance and monitors)
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/metrics/stack?ref=main'
```

## Uninstall

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=metrics bash
```

## What Gets Added

On top of the components provided by the core overlay, the metrics overlay adds:

| Component           | Namespace    | Description                                            |
|---------------------|--------------|--------------------------------------------------------|
| Prometheus Operator | `monitoring` | Manages Prometheus instances and monitors              |
| Prometheus instance | `monitoring` | Collects and stores metrics (400Mi memory, 1 replica)  |
| PodMonitors         | `monitoring` | Scrape targets for Kafka brokers and Strimzi operators |
| ServiceMonitor      | `monitoring` | Scrape target for StreamsHub Console operator          |

The overlay also patches existing resources:

- Kafka — enables the [Strimzi Metrics Reporter](https://strimzi.io/docs/operators/latest/deploying#proc-metrics-kafka-str) on the `dev-cluster`, exposing JMX metrics at `/metrics`
- Console — adds Prometheus as a metrics data source so the Console UI displays Kafka metrics

## How Metrics Flow

```
Kafka Broker → /metrics (Strimzi Metrics Reporter)
     ↓
PodMonitor (kafka-resources-metrics)
     ↓
Prometheus (prometheus-operated:9090)
     ↓
StreamsHub Console (displays metrics in UI)
```

Additional scrape targets:

- Strimzi Cluster Operator — scraped via `cluster-operator-metrics` PodMonitor
- Strimzi Entity Operator — scraped via `entity-operator-metrics` PodMonitor
- Console Operator — scraped via `streamshub-console-operator` ServiceMonitor
- Kubernetes cAdvisor — container-level resource metrics
- Kubernetes Kubelet — node-level metrics

## Accessing Prometheus

Port-forward to the Prometheus UI:

```shell
kubectl port-forward -n monitoring svc/prometheus-operated 9090:9090
```

Open [http://localhost:9090](http://localhost:9090) and navigate to **Status > Targets** to verify all scrape targets are up.

## Verify the Installation

Confirm the metrics components are running:

```shell
kubectl get prometheus -n monitoring
kubectl get podmonitor -n monitoring
kubectl get servicemonitor -n monitoring
```

Prometheus should reach `Available` status. Check that scrape targets are healthy:

```shell
kubectl port-forward -n monitoring svc/prometheus-operated 9090:9090 &
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"up"' | wc -l
```

Open the StreamsHub Console UI — Kafka cluster CPU and memory usage should show up straight away.
However, other metrics such as those for topics will only show once topics have been created and messages are flowing through them.

## Troubleshooting

### Metrics Not Appearing

If the Console UI does not show metrics (metrics overlay):

```shell
# Verify Prometheus is running
kubectl get prometheus -n monitoring

# Check Prometheus scrape targets
kubectl port-forward -n monitoring svc/prometheus-operated 9090:9090
# Visit http://localhost:9090/targets

# Verify PodMonitors are created
kubectl get podmonitor -n monitoring

# Verify Kafka has metrics enabled
kubectl get kafka/dev-cluster -n kafka -o jsonpath='{.spec.kafka.metricsConfig}'
```

**Common causes:**

- Metrics overlay not installed — verify you used `OVERLAY=metrics` during installation
- PodMonitor label mismatch — Prometheus selects PodMonitors with `app: strimzi`; verify the label is present
- Kafka metrics not enabled — the metrics overlay patches the Kafka CR to add `metricsConfig`; check that it was applied

