+++
title = 'Kroxylicious'
weight = 4
cpu_total = '3 CPU cores'
memory_total = '5 GiB'
+++

The Kroxylicious overlay extends the core stack with a [Kroxylicious](https://kroxylicious.io/) proxy deployed in front of the Strimzi-managed Kafka cluster, demonstrating request/response interception via a protocol filter.

## Quick-Start Install

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/install.sh | OVERLAY=kroxylicious bash
```

## Manual Install

If you prefer step-by-step control, the Kroxylicious overlay uses `overlays/kroxylicious` instead of `overlays/core`:

```shell
# Phase 1: Operators and CRDs
kubectl apply --server-side --force-conflicts -k 'https://github.com/streamshub/developer-quickstart//overlays/kroxylicious/base?ref=main'

# Optionally, wait for the operators to be ready
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s
kubectl wait --for=condition=Available deployment/kroxylicious-operator -n kroxylicious-operator --timeout=120s

# Phase 2: Operands and virtual cluster
kubectl apply -k 'https://github.com/streamshub/developer-quickstart//overlays/kroxylicious/stack?ref=main'
```

## Verify the Installation

Confirm the operator and proxy resources are ready:

```shell
kubectl get deployment -n kroxylicious-operator kroxylicious-operator
kubectl get kafkaproxy,kafkaproxyingress,kafkaservice,virtualkafkacluster,kafkaprotocolfilter -n kroxylicious
```

Check that the `VirtualKafkaCluster` reports `Accepted=True`:

```shell
kubectl get virtualkafkacluster dev-cluster -n kroxylicious -o yaml
```

Then follow [Trying It Out](#trying-it-out) above to confirm traffic actually flows through the proxy and the
`ProtocolLogger` filter is logging it.


## Uninstall

```shell
curl -sL https://raw.githubusercontent.com/streamshub/developer-quickstart/main/uninstall.sh | OVERLAY=kroxylicious bash
```

> **Note:** `uninstall.sh`'s shared-CRD detection (which retains CRDs still in use by other CRs on the
> cluster) only applies to Strimzi, Apicurio Registry, and the StreamsHub Console, since their CRDs are
> commonly used by workloads outside this quick-start. Kroxylicious's CRDs are always fully removed when
> the `kroxylicious` overlay is uninstalled, regardless of whether Strimzi/Apicurio/Console resources are
> shared, the same behavior the `metrics` overlay uses for Prometheus. If you have Kroxylicious resources
> managed outside this quick-start on the same cluster, back them up before uninstalling.

## What Gets Added

On top of the components provided by the core overlay, the Kroxylicious overlay adds:

| Component                             | Namespace               | Description                                                                      |
|----------------------------------------|--------------------------|-----------------------------------------------------------------------------------|
| Kroxylicious operator                  | `kroxylicious-operator`  | Reconciles `KafkaProxy`, `KafkaService`, `VirtualKafkaCluster`, and related CRs    |
| KafkaProxy `kroxylicious`              | `kroxylicious`           | The running proxy instance                                                        |
| KafkaProxyIngress `cluster-ip`         | `kroxylicious`           | Exposes the proxy via a ClusterIP Service                                         |
| KafkaService `dev-cluster`             | `kroxylicious`           | References the target Strimzi `dev-cluster` Kafka cluster                         |
| VirtualKafkaCluster `dev-cluster`      | `kroxylicious`           | The virtual cluster clients connect through                                       |
| KafkaProtocolFilter `protocol-logger`  | `kroxylicious`           | Logs PRODUCE, FETCH, and METADATA requests/responses at INFO level                 |

## Resource Requirements

The Kroxylicious overlay requires at least {{< param cpu_total >}} and {{< param memory_total >}} of allocatable cluster resources.
This includes the resources for the [core](core.md) stack plus the Kroxylicious components listed above.

## Accessing the Virtual Cluster

From inside the cluster, Kafka clients can connect through the virtual cluster at:

```
dev-cluster-cluster-ip-bootstrap.kroxylicious.svc.cluster.local:9292
```

To access it from outside the cluster, port-forward the Service:

```shell
kubectl port-forward -n kroxylicious svc/dev-cluster-cluster-ip-bootstrap 9292:9292
```

## What the Protocol Filter Does

The overlay attaches a single [`ProtocolLogger`](https://kroxylicious.io/) filter (`KafkaProtocolFilter/protocol-logger`)
to the virtual cluster. `ProtocolLogger` doesn't modify traffic: it's a read-only filter that logs the full
JSON representation of each Kafka request and response as it passes through the proxy, for `PRODUCE`, `FETCH`,
and `METADATA` API calls (configured via `spec.configTemplate.apiKeyNames`). It's a simple way to see,
concretely, that the proxy sits in the data path and is intercepting real protocol traffic between your
client and the Strimzi-managed `dev-cluster`, before reaching for a filter that actually changes behavior
(e.g. `RecordEncryption`, `Authorization`, or `RecordValidation`; see the
[Kroxylicious filter docs](https://kroxylicious.io/kroxylicious-filters/) for the full catalogue).

Each log entry is a JSON object with a `header` (`type`: `REQUEST`/`RESPONSE`, `apiKey`, `apiVersion`,
`correlationId`, `clientId`) followed by the decoded message body, so you can see, for example, the exact
topic names and partition assignments inside a `PRODUCE` request, or the record batch metadata in its
corresponding response.

### Trying It Out

Run a throwaway Kafka client pod inside the cluster and talk to the virtual cluster's bootstrap address
instead of the real Kafka cluster directly:

```shell
kubectl run kafka-client --restart=Never --image=quay.io/strimzi/kafka:1.1.0-kafka-4.3.0 -n kroxylicious \
  --command -- sleep 600
kubectl wait --for=condition=Ready pod/kafka-client -n kroxylicious --timeout=90s

# Create a topic through the proxy
kubectl exec -n kroxylicious kafka-client -- bin/kafka-topics.sh \
  --bootstrap-server dev-cluster-cluster-ip-bootstrap.kroxylicious.svc.cluster.local:9292 \
  --create --topic demo-topic --partitions 1 --replication-factor 1

# Produce a message through the proxy
kubectl exec -n kroxylicious kafka-client -- bash -c \
  'echo hello-kroxylicious | bin/kafka-console-producer.sh \
  --bootstrap-server dev-cluster-cluster-ip-bootstrap.kroxylicious.svc.cluster.local:9292 --topic demo-topic'

# Consume it back through the proxy
kubectl exec -n kroxylicious kafka-client -- bin/kafka-console-consumer.sh \
  --bootstrap-server dev-cluster-cluster-ip-bootstrap.kroxylicious.svc.cluster.local:9292 \
  --topic demo-topic --from-beginning --max-messages 1 --timeout-ms 20000
```

Then look at the proxy's logs to see the intercepted traffic:

```shell
kubectl logs -n kroxylicious deployment/kroxylicious | grep -A20 "ProtocolLoggerFilter"
```

You should see paired `REQUEST`/`RESPONSE` JSON log entries for the `PRODUCE` call (and `METADATA`/`FETCH`
calls made by the client/consumer as part of connecting). Clean up the test pod when you're done:

```shell
kubectl delete pod kafka-client -n kroxylicious
```

## Troubleshooting

### Proxy Resources Not Becoming Ready

```shell
# Check operator logs
kubectl logs -n kroxylicious-operator deployment/kroxylicious-operator

# Check conditions on each CR
kubectl get kafkaservice dev-cluster -n kroxylicious -o jsonpath='{.status.conditions}'
kubectl get virtualkafkacluster dev-cluster -n kroxylicious -o jsonpath='{.status.conditions}'
```

**Common causes:**

- Strimzi Kafka cluster not yet ready: the `KafkaService` requires `dev-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092` to be reachable
- Kroxylicious CRDs not installed: verify with `kubectl get crd | grep kroxylicious.io`
- Insufficient cluster resources: check node resource availability
