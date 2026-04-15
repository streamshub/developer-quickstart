///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.yaml:snakeyaml:2.6
//DEPS io.fabric8:kubernetes-model-core:7.6.1
//SOURCES ScriptUtils.java
//SOURCES CrdSchemaUtils.java

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verify that all Deployments and custom resources in the quickstart
 * have resource limits configured.
 *
 * <p>For Deployments, checks that every container has
 * {@code resources.requests} and {@code resources.limits} with both
 * {@code cpu} and {@code memory}.
 *
 * <p>For custom resources, introspects CRD OpenAPI v3 schemas to
 * discover {@code ResourceRequirements} fields, then checks that
 * the corresponding CR instances have those fields populated.
 * Optional features (where the parent path doesn't exist in the CR)
 * are skipped.
 *
 * <p>Also verifies that resource requests do not exceed limits.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code OVERLAY} — overlay name (default: "core")</li>
 * </ul>
 */
public class VerifyResourceLimits {

    private static final String DEFAULT_OVERLAY = "core";

    public static void main(String[] args) {
        String overlay = System.getenv().getOrDefault("OVERLAY", DEFAULT_OVERLAY);

        Path repoRoot = ScriptUtils.findRepoRoot();

        System.out.println("=== Verifying resource limits (overlay: " + overlay + ") ===");
        System.out.println();

        // Render both layers
        List<Map<String, Object>> baseDocs =
                ScriptUtils.runKustomize(repoRoot, "overlays/" + overlay + "/base", true);
        List<Map<String, Object>> stackDocs =
                ScriptUtils.runKustomize(repoRoot, "overlays/" + overlay + "/stack", true);

        List<String> errors = new ArrayList<>();

        // --- Check Deployments ---
        System.out.println("--- Checking Deployments ---");
        List<Map<String, Object>> deployments = baseDocs.stream()
                .filter(doc -> "Deployment".equals(doc.get("kind")))
                .collect(Collectors.toList());

        for (Map<String, Object> deployment : deployments) {
            errors.addAll(checkDeploymentResources(deployment));
        }
        System.out.println();

        // --- Walk CRD schemas ---
        System.out.println("--- CRD schema analysis ---");
        Map<String, List<String>> crdResourcePaths = new LinkedHashMap<>();

        List<Map<String, Object>> crds = baseDocs.stream()
                .filter(doc -> "CustomResourceDefinition".equals(doc.get("kind")))
                .collect(Collectors.toList());

        for (Map<String, Object> crd : crds) {
            String kind = CrdSchemaUtils.extractCrdKind(crd);
            if (kind == null) continue;

            Map<String, Object> schema = CrdSchemaUtils.extractCrdSchema(crd);
            if (schema == null) continue;

            // Walk from spec level
            Map<String, Object> specSchema = CrdSchemaUtils.getNestedMap(schema, "properties", "spec");
            if (specSchema == null) continue;

            List<String> paths = new ArrayList<>();
            CrdSchemaUtils.walkSchema(specSchema, ".spec", paths);

            // Filter out pod-level overhead paths (e.g., resources inside embedded PodTemplateSpec)
            paths = paths.stream()
                    .filter(p -> !CrdSchemaUtils.isPodSpecOverheadPath(p))
                    .collect(Collectors.toList());

            if (!paths.isEmpty()) {
                crdResourcePaths.put(kind, paths);
                System.out.println("  " + kind + ": " + paths.size()
                        + " ResourceRequirements path(s) found");
                paths.forEach(p -> System.out.println("    " + p));
            }
        }
        System.out.println();

        // --- Check CR instances ---
        System.out.println("--- Checking CR instances ---");

        for (Map<String, Object> doc : stackDocs) {
            String kind = (String) doc.get("kind");
            if (kind == null || !crdResourcePaths.containsKey(kind)) continue;

            ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(doc);
            List<String> paths = crdResourcePaths.get(kind);

            for (String path : paths) {
                errors.addAll(validateCrResourcePath(doc, ref, path));
            }
        }

        // --- Results ---
        System.out.println();
        if (errors.isEmpty()) {
            System.out.println("All resource limits verified successfully");
        } else {
            errors.forEach(e -> System.err.println("ERROR: " + e));
            System.err.println();
            System.err.println("FAILED: " + errors.size() + " resource limit violation(s) found");
            System.exit(1);
        }
    }

    // --- Deployment checking ---

    @SuppressWarnings("unchecked")
    static List<String> checkDeploymentResources(Map<String, Object> deployment) {
        List<String> errors = new ArrayList<>();
        ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(deployment);

        Map<String, Object> templateSpec = CrdSchemaUtils.getNestedMap(deployment, "spec", "template", "spec");
        if (templateSpec == null) return errors;

        Object containersObj = templateSpec.get("containers");
        if (!(containersObj instanceof List)) return errors;

        for (Object item : (List<?>) containersObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> container = (Map<String, Object>) item;
            String containerName = (String) container.getOrDefault("name", "<unknown>");
            String prefix = ref.namespace + ":" + ref.name + "/" + containerName;

            List<String> containerErrors = checkResourcesObject(
                    container.get("resources"), prefix);

            if (containerErrors.isEmpty()) {
                System.out.println("  " + prefix + " - OK");
            } else {
                errors.addAll(containerErrors);
            }
        }
        return errors;
    }

    // --- CR instance validation ---

    /**
     * Validate a single ResourceRequirements path on a CR instance.
     * If the parent path doesn't exist, the path is skipped (optional feature).
     * If the parent exists but resources are missing/incomplete, an error is reported.
     */
    @SuppressWarnings("unchecked")
    static List<String> validateCrResourcePath(Map<String, Object> cr,
                                                ScriptUtils.ResourceRef ref,
                                                String path) {
        // Split path like ".spec.entityOperator.topicOperator.resources"
        // into segments: ["spec", "entityOperator", "topicOperator", "resources"]
        String[] segments = path.substring(1).split("\\.");
        if (segments.length == 0) return List.of();

        // The last segment should be "resources" (the ResourceRequirements field itself)
        // Navigate to the parent and check if the resources field exists
        String resourcesKey = segments[segments.length - 1];
        String[] parentSegments = new String[segments.length - 1];
        System.arraycopy(segments, 0, parentSegments, 0, segments.length - 1);

        List<CrdSchemaUtils.ResolvedNode> parents = CrdSchemaUtils.resolvePath(cr, parentSegments, 0, "");
        if (parents.isEmpty()) {
            System.out.println("  " + ref.kind + "/" + ref.name + " " + path
                    + " - SKIPPED (not configured)");
            return List.of();
        }

        List<String> errors = new ArrayList<>();
        for (CrdSchemaUtils.ResolvedNode parent : parents) {
            if (!(parent.value instanceof Map)) continue;
            Map<String, Object> parentMap = (Map<String, Object>) parent.value;
            Object resources = parentMap.get(resourcesKey);
            String fullPath = ref.kind + "/" + ref.name + " " + parent.path + "." + resourcesKey;

            List<String> fieldErrors = checkResourcesObject(resources, fullPath);
            if (fieldErrors.isEmpty()) {
                System.out.println("  " + fullPath + " - OK");
            } else {
                errors.addAll(fieldErrors);
            }
        }
        return errors;
    }

    // --- Resource object validation ---

    /**
     * Check that a resources object has limits and requests with cpu and memory,
     * and that requests equal limits (Guaranteed QoS).
     */
    @SuppressWarnings("unchecked")
    static List<String> checkResourcesObject(Object resourcesObj, String prefix) {
        List<String> errors = new ArrayList<>();

        if (!(resourcesObj instanceof Map)) {
            errors.add(prefix + " missing resources");
            return errors;
        }

        Map<String, Object> resources = (Map<String, Object>) resourcesObj;

        Object requestsObj = resources.get("requests");
        Object limitsObj = resources.get("limits");

        if (!(requestsObj instanceof Map)) {
            errors.add(prefix + " missing resources.requests");
        } else {
            Map<String, Object> reqMap = (Map<String, Object>) requestsObj;
            if (!reqMap.containsKey("cpu")) errors.add(prefix + " missing resources.requests.cpu");
            if (!reqMap.containsKey("memory")) errors.add(prefix + " missing resources.requests.memory");
        }

        if (!(limitsObj instanceof Map)) {
            errors.add(prefix + " missing resources.limits");
        } else {
            Map<String, Object> limMap = (Map<String, Object>) limitsObj;
            if (!limMap.containsKey("cpu")) errors.add(prefix + " missing resources.limits.cpu");
            if (!limMap.containsKey("memory")) errors.add(prefix + " missing resources.limits.memory");
        }

        // Verify requests <= limits invariant
        errors.addAll(CrdSchemaUtils.checkRequestsNotExceedLimits(resources, prefix));

        return errors;
    }
}
