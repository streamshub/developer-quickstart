///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.fabric8:kubernetes-client:7.6.1
//DEPS org.yaml:snakeyaml:2.6
//SOURCES ScriptUtils.java

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Verify that all expected deployments and custom resources are ready
 * after installing the developer quickstart.
 *
 * <p>Derives the expected resources by running {@code kubectl kustomize}
 * against the overlay's layers, then uses the fabric8 Kubernetes client
 * to check their status on the cluster.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code OVERLAY} — overlay name (default: "core")</li>
 *   <li>{@code CONDITION_OVERRIDES} — space-separated apiGroup=Condition pairs (default condition: Ready)</li>
 *   <li>{@code TIMEOUT} — wait timeout with unit suffix, e.g. "300s", "5m" (default: "600s")</li>
 * </ul>
 */
public class VerifyInstall {

    private static final String DEFAULT_OVERLAY = "core";
    private static final String DEFAULT_TIMEOUT = "600s";

    public static void main(String[] args) {
        String overlay = System.getenv().getOrDefault("OVERLAY", DEFAULT_OVERLAY);
        String conditionOverridesEnv = System.getenv().getOrDefault("CONDITION_OVERRIDES", "");
        long timeoutSeconds = ScriptUtils.parseTimeout(System.getenv().getOrDefault("TIMEOUT", DEFAULT_TIMEOUT));

        Map<String, String> conditionOverrides = parseConditionOverrides(conditionOverridesEnv);

        Path repoRoot = ScriptUtils.findRepoRoot();
        Path overlayDir = repoRoot.resolve("overlays").resolve(overlay);

        System.out.println("=== Verifying install (overlay: " + overlay + ") ===");
        System.out.println();

        // Discover all resources from all layers of this overlay (strict mode)
        List<Map<String, Object>> allDocs = ScriptUtils.kustomizeAllLayers(repoRoot, overlayDir, true);

        // Step 1: Extract expected deployments
        List<ScriptUtils.ResourceRef> expectedDeployments = allDocs.stream()
                .filter(doc -> "Deployment".equals(doc.get("kind")))
                .map(ScriptUtils.ResourceRef::fromManifest)
                .collect(Collectors.toList());

        // Step 2: Extract expected custom resources (everything not infra or Deployment)
        List<ScriptUtils.ResourceRef> expectedCRs = allDocs.stream()
                .filter(doc -> !ScriptUtils.INFRA_KINDS.contains(doc.get("kind")))
                .filter(doc -> !"Deployment".equals(doc.get("kind")))
                .map(ScriptUtils.ResourceRef::fromManifest)
                .collect(Collectors.toList());

        System.out.println("Expected deployments:");
        expectedDeployments.forEach(d -> System.out.println("  " + d));
        System.out.println("Expected custom resources:");
        expectedCRs.forEach(cr -> System.out.println("  " + cr));
        System.out.println();

        boolean allPassed = true;

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            // Step 3: Wait for deployments to be ready
            for (ScriptUtils.ResourceRef ref : expectedDeployments) {
                System.out.println("--- Deployment " + ref.name + " (" + ref.namespace
                        + ") waiting for readiness ---");
                try {
                    Deployment deployment = client.apps().deployments()
                            .inNamespace(ref.namespace)
                            .withName(ref.name)
                            .waitUntilReady(timeoutSeconds, TimeUnit.SECONDS);
                    if (deployment != null) {
                        System.out.println("  Deployment " + ref.name + " is ready");
                    } else {
                        System.err.println("ERROR: Deployment not found: " + ref);
                        allPassed = false;
                    }
                } catch (Exception e) {
                    System.err.println("ERROR: Deployment " + ref.name
                            + " did not become ready within " + timeoutSeconds + "s: " + e.getMessage());
                    allPassed = false;
                }
            }
            System.out.println();

            // Step 4: Wait for custom resources to be ready
            for (ScriptUtils.ResourceRef ref : expectedCRs) {
                String condition = conditionOverrides.getOrDefault(ref.apiGroup(), "Ready");
                System.out.println("--- " + ref.kind + "/" + ref.name
                        + " (" + ref.namespace + ") waiting for condition=" + condition + " ---");

                try {
                    var resourceClient = client.genericKubernetesResources(ref.apiGroupVersion, ref.kind)
                            .inNamespace(ref.namespace)
                            .withName(ref.name);

                    boolean ready = waitForCondition(resourceClient, condition, timeoutSeconds);
                    if (ready) {
                        System.out.println("  " + ref.kind + "/" + ref.name + " is " + condition);
                    } else {
                        System.err.println("ERROR: " + ref.kind + "/" + ref.name
                                + " did not reach condition=" + condition + " within " + timeoutSeconds + "s");
                        allPassed = false;
                    }
                } catch (Exception e) {
                    System.err.println("ERROR: Failed to check " + ref + ": " + e.getMessage());
                    allPassed = false;
                }
            }

            // Step 5: Verify resource limits on all quickstart pods
            System.out.println();
            System.out.println("--- Verifying pod resource limits ---");

            Set<String> namespaces = allDocs.stream()
                    .map(ScriptUtils::extractNamespace)
                    .filter(ns -> ns != null && !ns.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            for (String ns : namespaces) {
                List<Pod> pods = client.pods().inNamespace(ns)
                        .withLabel("app.kubernetes.io/part-of", "streamshub-developer-quickstart")
                        .list().getItems();

                for (Pod pod : pods) {
                    String podName = pod.getMetadata().getName();
                    for (Container container : pod.getSpec().getContainers()) {
                        String containerName = container.getName();
                        ResourceRequirements resources = container.getResources();
                        List<String> missing = new ArrayList<>();

                        if (resources == null || resources.getRequests() == null
                                || resources.getRequests().isEmpty()) {
                            missing.add("requests");
                        } else {
                            if (!resources.getRequests().containsKey("cpu"))
                                missing.add("requests.cpu");
                            if (!resources.getRequests().containsKey("memory"))
                                missing.add("requests.memory");
                        }

                        if (resources == null || resources.getLimits() == null
                                || resources.getLimits().isEmpty()) {
                            missing.add("limits");
                        } else {
                            if (!resources.getLimits().containsKey("cpu"))
                                missing.add("limits.cpu");
                            if (!resources.getLimits().containsKey("memory"))
                                missing.add("limits.memory");
                        }

                        if (!missing.isEmpty()) {
                            System.err.println("ERROR: Pod " + ns + "/" + podName
                                    + " container " + containerName
                                    + " missing: " + String.join(", ", missing));
                            allPassed = false;
                        } else {
                            System.out.println("  Pod " + ns + "/" + podName
                                    + " container " + containerName + " - OK");
                        }
                    }
                }
            }
        }

        if (!allPassed) {
            System.err.println();
            System.err.println("FAILED: Not all resources are ready");
            System.exit(1);
        }

        System.out.println();
        System.out.println("All resources verified successfully");
    }

    /**
     * Poll for a condition on a generic Kubernetes resource.
     * Fabric8's waitUntilCondition works with typed resources; for generic resources
     * we poll manually.
     */
    static boolean waitForCondition(Resource<GenericKubernetesResource> resource,
                                    String conditionType, long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < deadline) {
            GenericKubernetesResource res = resource.get();
            if (res != null && hasCondition(res, conditionType)) {
                return true;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Check if a generic resource has a condition with the given type set to "True".
     */
    @SuppressWarnings("unchecked")
    static boolean hasCondition(GenericKubernetesResource resource, String conditionType) {
        Object status = resource.getAdditionalProperties().get("status");
        if (!(status instanceof Map)) return false;

        Object conditions = ((Map<String, Object>) status).get("conditions");
        if (!(conditions instanceof List)) return false;

        for (Object c : (List<?>) conditions) {
            if (c instanceof Map) {
                Map<String, Object> condition = (Map<String, Object>) c;
                if (conditionType.equals(condition.get("type"))
                        && "True".equals(String.valueOf(condition.get("status")))) {
                    return true;
                }
            }
        }
        return false;
    }

    static Map<String, String> parseConditionOverrides(String env) {
        Map<String, String> overrides = new HashMap<>();
        if (env == null || env.isBlank()) return overrides;
        for (String pair : env.trim().split("\\s+")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                overrides.put(parts[0], parts[1]);
            }
        }
        return overrides;
    }
}
