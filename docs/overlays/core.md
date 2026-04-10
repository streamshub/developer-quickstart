+++
title = 'Core'
weight = 0
cpu_total = '3 CPU cores'
memory_total = '4.5 GiB'
+++

# Core Overlay

The core overlay is the default deployment. 
It installs the base event-streaming stack without any optional extensions.

## Quick-Start Install

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | bash
```

No `OVERLAY` variable is needed — the core overlay is used by default.

## Manual Install

```shell
# Phase 1 — Operators and CRDs
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/core/base?ref=main'

# Optionally, wait for the operators to be ready
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/apicurio-registry-operator -n apicurio-registry --timeout=120s
kubectl wait --for=condition=Available deployment/streamshub-console-operator -n streamshub-console --timeout=120s

# Phase 2 — Operands
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/core/stack?ref=main'
```

## Uninstall

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | bash
```

## Components

| Component                 | Namespace            | Description                                       |
|---------------------------|----------------------|---------------------------------------------------|
| Strimzi Kafka Operator    | `strimzi`            | Manages Kafka clusters via CRDs                   |
| Apicurio Registry Operator| `apicurio-registry`  | Manages schema registry instances                 |
| StreamsHub Console Operator| `streamshub-console`| Manages the Console web UI                        |
| Kafka cluster             | `kafka`              | Single-node Kafka cluster (`dev-cluster`)         |
| Apicurio Registry         | `apicurio-registry`  | Schema registry instance with app and UI          |
| StreamsHub Console        | `streamshub-console` | Web UI for managing Kafka clusters                |

## Resource Requirements

The core overlay requires at least {{< param cpu_total >}} and {{< param memory_total >}} of allocatable cluster resources.
