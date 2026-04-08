+++
title = 'Accessing Services'
weight = 3
+++

# Accessing Services

## StreamsHub Console

### Ingress Access

For the Console to startup correctly and be accessible, you need an Ingress controller running in your cluster.

#### minikube

Enable the ingress addon (if not already enabled):

```shell
minikube addons enable ingress
```

Use port-forwarding to access the console:

```bash
kubectl port-forward -n streamshub-console svc/streamshub-console-console-service 8090:80
```

Open [http://localhost:8090](http://localhost:8090) in your browser.

#### KIND

If you created your KIND cluster with the port mappings described in [Prerequisites](prerequisites.md), and deployed ingress-nginx:
Use port-forwarding to access the console:

```bash
kubectl port-forward -n streamshub-console svc/streamshub-console-console-service 8090:80
```

Open [http://localhost:8090](http://localhost:8090) in your browser.


## Kafka

The Kafka cluster is accessible within the Kubernetes cluster at:

```
dev-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092
```

### Port-Forwarding

To access Kafka from outside the cluster:

```shell
kubectl port-forward -n kafka svc/dev-cluster-kafka-bootstrap 9092:9092
```

Then connect your client to `localhost:9092`.

### Producing and Consuming Messages

From within the cluster, you can use the Kafka CLI tools:

```shell
# Start a producer
kubectl run kafka-producer -it --rm --image=quay.io/strimzi/kafka:0.51.0-kafka-4.2.0 \
  --restart=Never -- bin/kafka-console-producer.sh \
  --bootstrap-server dev-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092 \
  --topic test

# Start a consumer (in a separate terminal)
kubectl run kafka-consumer -it --rm --image=quay.io/strimzi/kafka:0.51.0-kafka-4.2.0 \
  --restart=Never -- bin/kafka-console-consumer.sh \
  --bootstrap-server dev-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092 \
  --topic test --from-beginning
```

## Apicurio Registry

The registry has two services accessible within the cluster:

- API: `apicurio-registry-app-service.apicurio-registry.svc.cluster.local:8080`
- UI: `apicurio-registry-ui-service.apicurio-registry.svc.cluster.local:8080`

### Port-Forwarding

The UI is a browser application that connects to the API backend at `localhost:8080` by default.
To use the UI, you must port-forward **both** the API and UI services.

#### API

The API service must be forwarded to port 8080 for the UI to function:

```shell
kubectl port-forward -n apicurio-registry svc/apicurio-registry-app-service 8080:8080
```

You can query the API directly:

```shell
curl http://localhost:8080/apis/registry/v3/search/artifacts
```

#### UI

In a separate terminal, forward the UI service:

```shell
kubectl port-forward -n apicurio-registry svc/apicurio-registry-ui-service 8081:8080
```

Open [http://localhost:8081](http://localhost:8081) in your browser.

## Overlay Services

If you installed with an overlay, it may deploy additional services. 
See the specific overlay page under [Overlays](overlays/_index.md) for access instructions.
