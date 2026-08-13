+++
title = 'Overlays'
weight = 4
+++

The developer quick-start uses [Kustomize overlays](architecture.md) to provide optional extensions on top of the core stack. 
Each overlay adds components that integrate with the base deployment.

To install with an overlay, set the `OVERLAY` environment variable:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=<name> bash
```

To uninstall, use the same `OVERLAY` value:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=<name> bash
```

## Available Overlays

| Overlay                         | Description                                                                                                                          |
|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| [core](core.md)                 | The default stack: Strimzi, Kafka, Apicurio Registry, and StreamsHub Console. No `OVERLAY` variable needed.                          |
| [metrics](metrics.md)           | Adds Prometheus Operator, a Prometheus instance, and Kafka metrics collection via PodMonitors. Wires the Console to display metrics.  |
| [mcp](mcp.md)                   | Adds the StreamsHub MCP server, giving AI assistants read-only access to the Strimzi-managed Kafka cluster.                          |
| [mcp-metrics](mcp-metrics.md)   | Combines Prometheus monitoring with the StreamsHub MCP server. The MCP server uses Prometheus as its centralized metrics provider.    |

## Developing Overlays

See [Developing Overlays](developing.md) for the directory structure, resource limit requirements, and CI checks that apply when adding or modifying overlays.
