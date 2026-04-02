+++
title = 'Developer Quick-Start'
linkTitle = 'Developer Quick-Start'
weight = 0
[[cascade]]
    type = 'docs'
+++

# Developer Quick-Start

Please make sure you have all the [prerequisites](prerequisites.md) covered first.
Then you can deploy a complete, open-source, event-streaming stack on your local Kubernetes cluster (e.g. MiniKube, KIND) with a single command:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | bash
```

The script installs operators, waits for readiness, then deploys all services. 
The full stack is typically ready in under five minutes.

> **Note:** This is a **development-only** configuration. Resource limits, security settings, and storage are not suitable for production use.

## What Gets Deployed

| Component                     | Namespace            | Description                       |
|-------------------------------|----------------------|-----------------------------------|
| Strimzi Kafka Operator        | `strimzi`            | Manages Kafka clusters via CRDs   |
| Kafka cluster (`dev-cluster`) | `kafka`              | Single-node Kafka for development |
| Apicurio Registry Operator    | `apicurio-registry`  | Manages schema registry instances |
| Apicurio Registry             | `apicurio-registry`  | In-memory schema registry         |
| StreamsHub Console Operator   | `streamshub-console` | Manages console instances         |
| StreamsHub Console            | `streamshub-console` | Web UI for Kafka management       |

Optional [overlays](overlays/_index.md) can extend the core stack with additional components such as Prometheus metrics. Install with an overlay by setting the `OVERLAY` variable:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=<name> bash
```

## Next Steps

- [Prerequisites](prerequisites.md) — what you need before installing.
- [Installation](installation.md) — all installation methods and options.
- [Accessing Services](accessing-services.md) — open the Console, connect to Kafka.
- [Overlays](overlays/) - look at the what additional components and features can be added with the provided overlays.
- [Uninstallation](uninstallation.md) — safe teardown on any cluster.
- [Troubleshooting](troubleshooting.md) - help with common issues.