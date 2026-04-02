+++
title = 'Troubleshooting'
weight = 7
+++

# Troubleshooting

## Operator Not Becoming Ready

If an operator deployment does not reach `Ready`/`Available` within the timeout:

```shell
# Check the deployment status
kubectl describe deployment/strimzi-cluster-operator -n strimzi

# Check operator pod logs
kubectl logs -n strimzi deployment/strimzi-cluster-operator

# Check for pending pods (resource constraints)
kubectl get pods -n strimzi -o wide
```

**Common causes:**

- Insufficient resources — the cluster may not have enough CPU or memory. This can often be an issue if you have selected an overlay which multiple components on top of the core. Try increasing resources assigned to your Kubernetes cluster: `minikube start --cpus=8 --memory=12g`
- Image pull errors — check that the cluster can reach container registries (quay.io, docker.io)
- Timeout too short — increase with `TIMEOUT=300s`

## Custom Resource Not Reconciling

If a Kafka, Registry, or Console resource is stuck:

```shell
# Check CR status and conditions
kubectl describe kafka/dev-cluster -n kafka

# Check events in the namespace
kubectl get events -n kafka --sort-by='.lastTimestamp'

# Check the managing operator's logs
kubectl logs -n strimzi deployment/strimzi-cluster-operator --tail=100
```

**Common causes:**

- Operator not running — verify the operator deployment is ready before deploying operands
- CRDs not registered — if you skipped Phase 1 waits, CRDs may not be ready yet
- Namespace missing — the kustomization creates namespaces, but manual partial installs may miss them

## Console Cannot Reach Kafka

If the Console shows the Kafka cluster as unreachable:

```shell
# Verify Console configuration
kubectl get console/streamshub-console -n streamshub-console -o yaml

# Check the Console operator logs
kubectl logs -n streamshub-console deployment/streamshub-console-operator --tail=100

# Verify the Kafka bootstrap service exists
kubectl get svc/dev-cluster-kafka-bootstrap -n kafka
```

**Common causes:**

- Kafka not ready — the Kafka cluster may still be starting. Check `kubectl get kafka -n kafka`
- Listener mismatch — the Console is configured to use the `plain` listener; verify the Kafka cluster exposes it
- Network policy — if your cluster has network policies, ensure cross-namespace traffic is allowed

## CRD Conflicts on Shared Clusters

If the cluster already has Strimzi, Apicurio, or Console CRDs from another deployment:

- **Install** may show `already exists` warnings — this is usually safe. The quick-start will use the existing CRDs
- **Uninstall** will detect shared CRDs and retain them. The uninstall script reports which operator groups were retained and why

To manually check for shared resources:

```shell
kubectl get kafkas -A --selector='app.kubernetes.io/part-of!=streamshub-developer-quickstart'
kubectl get apicurioregistry3 -A --selector='app.kubernetes.io/part-of!=streamshub-developer-quickstart'
kubectl get console.console.streamshub.github.com -A --selector='app.kubernetes.io/part-of!=streamshub-developer-quickstart'
```

## Collecting Diagnostics

For comprehensive diagnostic output, use the debug script:

```shell
OVERLAY=core jbang .github/scripts/Debug.java
```

This dumps CR status, events, pod listings, and logs for all quick-start namespaces. With the metrics overlay:

```shell
OVERLAY=metrics jbang .github/scripts/Debug.java
```
