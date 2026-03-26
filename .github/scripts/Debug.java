///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.fabric8:kubernetes-client:7.6.1
//DEPS org.yaml:snakeyaml:2.6
//SOURCES ScriptUtils.java

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dump diagnostic information for debugging failed smoke tests.
 *
 * <p>Derives the expected resources and namespaces from {@code kubectl kustomize}
 * output, then uses the fabric8 Kubernetes client to collect CR status, events,
 * pod listings, and pod logs.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code OVERLAY} — overlay name (default: "core")</li>
 *   <li>{@code LOG_TAIL_LINES} — number of log lines per pod (default: 30)</li>
 * </ul>
 */
public class Debug {

    private static final String DEFAULT_OVERLAY = "core";
    private static final int DEFAULT_LOG_TAIL_LINES = 30;

    public static void main(String[] args) {
        String overlay = System.getenv().getOrDefault("OVERLAY", DEFAULT_OVERLAY);
        int logTailLines = parseIntEnv("LOG_TAIL_LINES", DEFAULT_LOG_TAIL_LINES);

        Path repoRoot = ScriptUtils.findRepoRoot();
        Path overlayDir = repoRoot.resolve("overlays").resolve(overlay);

        // Discover expected CRs and namespaces from all overlay layers (best-effort)
        List<Map<String, Object>> allDocs = ScriptUtils.kustomizeAllLayers(repoRoot, overlayDir, false);

        // Collect CR references — everything that isn't infra or a Deployment
        List<ScriptUtils.ResourceRef> customResources = allDocs.stream()
                .filter(doc -> !ScriptUtils.INFRA_KINDS.contains(doc.get("kind")))
                .filter(doc -> !"Deployment".equals(doc.get("kind")))
                .map(ScriptUtils.ResourceRef::fromManifest)
                .collect(Collectors.toList());

        // Collect all namespaces
        Set<String> namespaces = allDocs.stream()
                .map(ScriptUtils::extractNamespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            // CR status
            System.out.println("=== CR status ===");
            for (ScriptUtils.ResourceRef cr : customResources) {
                try {
                    GenericKubernetesResource resource = client
                            .genericKubernetesResources(cr.apiGroupVersion, cr.kind)
                            .inNamespace(cr.namespace)
                            .withName(cr.name)
                            .get();
                    if (resource != null) {
                        Yaml yaml = new Yaml();
                        System.out.println(yaml.dump(resource.getAdditionalProperties()));
                    } else {
                        System.out.println(cr.kind + "/" + cr.name + " in " + cr.namespace + ": NOT FOUND");
                    }
                } catch (Exception e) {
                    System.out.println(cr.kind + "/" + cr.name + " in " + cr.namespace + ": ERROR - " + e.getMessage());
                }
            }

            // Events — scoped to discovered namespaces
            System.out.println();
            System.out.println("=== Events ===");
            for (String ns : namespaces) {
                List<Event> events = client.v1().events().inNamespace(ns).list().getItems();
                events.stream()
                        .sorted(Comparator.comparing(
                                e -> e.getLastTimestamp() != null ? e.getLastTimestamp() : "",
                                Comparator.naturalOrder()))
                        .skip(Math.max(0, events.size() - 50))
                        .forEach(e -> System.out.printf("  %s  %s/%s  %s: %s%n",
                                e.getLastTimestamp(),
                                e.getInvolvedObject().getNamespace(),
                                e.getInvolvedObject().getName(),
                                e.getReason(),
                                e.getMessage()));
            }

            // Pods
            System.out.println();
            System.out.println("=== Pods (all namespaces) ===");
            client.pods().inAnyNamespace().list().getItems().forEach(pod ->
                    System.out.printf("  %-20s %-50s %-10s%n",
                            pod.getMetadata().getNamespace(),
                            pod.getMetadata().getName(),
                            pod.getStatus().getPhase()));

            // Per-namespace pod details and logs
            for (String ns : namespaces) {
                System.out.println();
                System.out.println("=== Pods in " + ns + " ===");
                List<Pod> pods = client.pods().inNamespace(ns).list().getItems();
                pods.forEach(pod -> System.out.printf("  %-50s %-10s  node=%-20s%n",
                        pod.getMetadata().getName(),
                        pod.getStatus().getPhase(),
                        pod.getSpec().getNodeName()));

                System.out.println("=== Pod logs in " + ns + " ===");
                for (Pod pod : pods) {
                    String podName = pod.getMetadata().getName();
                    System.out.println("--- pod/" + podName + " ---");
                    try {
                        String log = client.pods().inNamespace(ns)
                                .withName(podName)
                                .tailingLines(logTailLines)
                                .getLog();
                        System.out.println(log);
                    } catch (Exception e) {
                        System.out.println("  (unable to fetch logs: " + e.getMessage() + ")");
                    }
                }
            }
        }
    }

    private static int parseIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("WARNING: Could not parse " + name + "='" + value + "', defaulting to " + defaultValue);
            return defaultValue;
        }
    }
}
