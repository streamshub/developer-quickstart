+++
title = 'Prerequisites'
weight = 1
+++

# Prerequisites

## Required

- **kubectl v1.27 or later** — the install scripts use Kustomize v5.0 features (the `labels` transformer) which require kubectl 1.27+.

  Check your version:

  ```shell
  kubectl version --client
  ```

- **A running Kubernetes cluster** — any local or development cluster should work. Common options:
  - [minikube](https://minikube.sigs.k8s.io/docs/start/)
  - [KIND](https://kind.sigs.k8s.io/docs/user/quick-start/)

## Recommended

- **An Ingress controller** — required if you want to access the StreamsHub Console via its hostname (`console.streamshub.local`). Without one, you can still use port-forwarding to access Kafka.

## Cluster Setup

If you don't already have a cluster running, here are quick setup instructions for the most common options:

### Minikube

```shell
minikube start --cpus=4 --memory=8g --addons=ingress
```

### KIND

KIND requires port mappings configured at cluster creation for Ingress to work:

```shell
cat <<EOF | kind create cluster --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
EOF
```

Then deploy an Ingress controller:

```shell
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/kind/deploy.yaml
kubectl wait --namespace ingress-nginx \
  --for=condition=Ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s
```
