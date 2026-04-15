import io.fabric8.kubernetes.api.model.Quantity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for CRD schema introspection and resource path resolution.
 *
 * <p>Used by both {@code VerifyResourceLimits} and {@code VerifyDocumentedResources}
 * to discover {@code ResourceRequirements} fields in CRD OpenAPI v3 schemas
 * and resolve those paths against CR instances.
 */
public class CrdSchemaUtils {

    private CrdSchemaUtils() { }

    /**
     * Extract the kind name from a CRD document.
     */
    static String extractCrdKind(Map<String, Object> crd) {
        Map<String, Object> names = getNestedMap(crd, "spec", "names");
        return names != null ? (String) names.get("kind") : null;
    }

    /**
     * Extract the OpenAPI v3 schema from the first version of a CRD.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> extractCrdSchema(Map<String, Object> crd) {
        Map<String, Object> spec = getMap(crd, "spec");
        if (spec == null) return null;

        Object versionsObj = spec.get("versions");
        if (!(versionsObj instanceof List)) return null;

        List<?> versions = (List<?>) versionsObj;
        if (versions.isEmpty()) return null;

        Object firstVersion = versions.get(0);
        if (!(firstVersion instanceof Map)) return null;

        return getNestedMap((Map<String, Object>) firstVersion, "schema", "openAPIV3Schema");
    }

    /**
     * Recursively walk a CRD schema to find all ResourceRequirements fields.
     * Records the JSON path for each field found.
     *
     * <p>This method finds ALL ResourceRequirements fields without filtering.
     * Callers that need to skip certain paths (e.g., pod-level overhead fields
     * inside embedded PodTemplateSpecs) should filter the results using
     * {@link #isPodSpecOverheadPath(String)}.
     */
    @SuppressWarnings("unchecked")
    static void walkSchema(Map<String, Object> schemaNode, String currentPath, List<String> result) {
        if (schemaNode == null) return;

        Map<String, Object> properties = getMap(schemaNode, "properties");
        if (properties == null) return;

        if (isResourceRequirements(properties)) {
            result.add(currentPath);
            return;
        }

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;

            Map<String, Object> childSchema = (Map<String, Object>) entry.getValue();
            String childPath = currentPath + "." + entry.getKey();
            String type = (String) childSchema.get("type");

            if ("array".equals(type)) {
                Map<String, Object> items = getMap(childSchema, "items");
                if (items != null) {
                    walkSchema(items, childPath + "[]", result);
                }
            } else {
                walkSchema(childSchema, childPath, result);
            }
        }
    }

    /**
     * Detect a ResourceRequirements field by its OpenAPI schema signature.
     * Must have "limits" and "requests" properties where both have
     * additionalProperties with x-kubernetes-int-or-string: true.
     */
    static boolean isResourceRequirements(Map<String, Object> properties) {
        if (!properties.containsKey("limits") || !properties.containsKey("requests")) {
            return false;
        }

        return hasIntOrStringAdditionalProperties(properties.get("limits"))
            && hasIntOrStringAdditionalProperties(properties.get("requests"));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasIntOrStringAdditionalProperties(Object fieldObj) {
        if (!(fieldObj instanceof Map)) return false;
        Map<String, Object> field = (Map<String, Object>) fieldObj;
        Object addProps = field.get("additionalProperties");
        if (!(addProps instanceof Map)) return false;
        return Boolean.TRUE.equals(((Map<String, Object>) addProps).get("x-kubernetes-int-or-string"));
    }

    /**
     * Check whether a ResourceRequirements path represents a pod-level
     * overhead field embedded in a PodTemplateSpec, rather than a
     * component-level resource requirement.
     *
     * <p>Pod-level resources (added in k8s 1.30) appear as siblings of
     * {@code containers} inside embedded PodSpec structures like
     * {@code template.spec} or {@code podTemplateSpec.spec}. These are
     * infrastructure overhead and should not be required in CR configs.
     *
     * <p>CRD-level resources (e.g., Prometheus {@code spec.resources}) that
     * happen to be siblings of {@code containers} are NOT filtered by this
     * method — they appear at the CRD spec level, not inside an embedded
     * PodTemplateSpec.
     *
     * @param path the dot-separated path (e.g., ".spec.app.podTemplateSpec.spec.resources")
     * @return true if this is a pod-level overhead path that should be skipped
     */
    static boolean isPodSpecOverheadPath(String path) {
        return path.matches(".*\\.template\\.spec\\.resources$")
            || path.matches(".*\\.podTemplateSpec\\.spec\\.resources$");
    }

    /**
     * Resolve a path through a document, handling array segments (ending with []).
     * Returns all leaf values reached along with their resolved paths.
     */
    @SuppressWarnings("unchecked")
    static List<ResolvedNode> resolvePath(Object current, String[] segments, int index, String pathSoFar) {
        if (index >= segments.length) {
            return List.of(new ResolvedNode(pathSoFar, current));
        }

        String segment = segments[index];

        if (segment.endsWith("[]")) {
            String key = segment.substring(0, segment.length() - 2);
            if (!(current instanceof Map)) return List.of();
            Object listObj = ((Map<String, Object>) current).get(key);
            if (!(listObj instanceof List)) return List.of();

            List<?> list = (List<?>) listObj;
            List<ResolvedNode> results = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                results.addAll(resolvePath(list.get(i), segments, index + 1,
                        pathSoFar + "." + key + "[" + i + "]"));
            }
            return results;
        } else {
            if (!(current instanceof Map)) return List.of();
            Object child = ((Map<String, Object>) current).get(segment);
            if (child == null) return List.of();
            return resolvePath(child, segments, index + 1, pathSoFar + "." + segment);
        }
    }

    // --- Kubernetes quantity parsing (delegates to Fabric8 Quantity) ---

    private static final BigDecimal MILLIS_PER_CORE = BigDecimal.valueOf(1000);
    private static final BigDecimal BYTES_PER_MIB = BigDecimal.valueOf(1_048_576);

    /**
     * Parse a Kubernetes CPU quantity to millicores.
     *
     * <p>Handles all Kubernetes quantity formats via Fabric8 {@link Quantity},
     * including millicore suffixes ({@code "500m"}), whole/fractional cores
     * ({@code "1"}, {@code "0.5"}), and values parsed by SnakeYAML as
     * {@link Integer} or {@link Double}.
     *
     * @param value the CPU quantity (String, Integer, or Double)
     * @return the value in millicores
     */
    static long parseCpuMillis(Object value) {
        Quantity q = Quantity.parse(String.valueOf(value));
        return q.getNumericalAmount().multiply(MILLIS_PER_CORE).longValue();
    }

    /**
     * Parse a Kubernetes memory quantity to MiB.
     *
     * <p>Handles all Kubernetes quantity formats via Fabric8 {@link Quantity},
     * including binary suffixes ({@code Ki}, {@code Mi}, {@code Gi}, {@code Ti},
     * {@code Pi}, {@code Ei}), decimal suffixes ({@code k}, {@code M}, {@code G},
     * {@code T}, {@code P}, {@code E}), exponent notation, and plain byte counts.
     *
     * @param value the memory quantity (String, Integer, or Double)
     * @return the value in MiB (rounded half-up)
     */
    static long parseMemoryMiB(Object value) {
        Quantity q = Quantity.parse(String.valueOf(value));
        BigDecimal bytes = Quantity.getAmountInBytes(q);
        return bytes.divide(BYTES_PER_MIB, 0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * Check that {@code resources.requests} does not exceed {@code resources.limits}
     * for both CPU and memory.
     *
     * <p>Uses numeric comparison via Fabric8 {@link Quantity} so semantically
     * equal values in different formats (e.g., {@code "1"} vs {@code "1000m"})
     * are treated as equal.
     *
     * @param resources the resources map (with "requests" and "limits" sub-maps)
     * @param prefix    a human-readable prefix for error messages
     * @return list of invariant violation messages (empty if requests &lt;= limits)
     */
    @SuppressWarnings("unchecked")
    static List<String> checkRequestsNotExceedLimits(Map<String, Object> resources, String prefix) {
        List<String> errors = new ArrayList<>();
        if (resources == null) return errors;

        Object requestsObj = resources.get("requests");
        Object limitsObj = resources.get("limits");
        if (!(requestsObj instanceof Map) || !(limitsObj instanceof Map)) return errors;

        Map<String, Object> requests = (Map<String, Object>) requestsObj;
        Map<String, Object> limits = (Map<String, Object>) limitsObj;

        if (requests.containsKey("cpu") && limits.containsKey("cpu")) {
            long reqCpu = parseCpuMillis(requests.get("cpu"));
            long limCpu = parseCpuMillis(limits.get("cpu"));
            if (reqCpu > limCpu) {
                errors.add(prefix + " requests.cpu (" + reqCpu
                        + "m) > limits.cpu (" + limCpu + "m)");
            }
        }
        if (requests.containsKey("memory") && limits.containsKey("memory")) {
            long reqMem = parseMemoryMiB(requests.get("memory"));
            long limMem = parseMemoryMiB(limits.get("memory"));
            if (reqMem > limMem) {
                errors.add(prefix + " requests.memory (" + reqMem
                        + "Mi) > limits.memory (" + limMem + "Mi)");
            }
        }

        return errors;
    }

    // --- Utilities ---

    @SuppressWarnings("unchecked")
    static Map<String, Object> getMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    static Map<String, Object> getNestedMap(Map<String, Object> root, String... keys) {
        Map<String, Object> current = root;
        for (String key : keys) {
            current = getMap(current, key);
            if (current == null) return null;
        }
        return current;
    }

    static class ResolvedNode {
        final String path;
        final Object value;

        ResolvedNode(String path, Object value) {
            this.path = path;
            this.value = value;
        }
    }
}
