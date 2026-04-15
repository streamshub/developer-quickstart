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
 * Show a per-component resource breakdown for an overlay and suggest
 * frontmatter values for the overlay's documentation page.
 *
 * <p>This is a developer helper tool — it always exits successfully
 * (unless {@code kustomize} itself fails). It does not enforce any
 * rules; use {@code VerifyResourceLimits} and
 * {@code VerifyDocumentedResources} for CI verification.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code OVERLAY} — overlay name (default: "core")</li>
 * </ul>
 */
public class ShowOverlayResources {

    private static final String DEFAULT_OVERLAY = "core";

    public static void main(String[] args) {
        String overlay = System.getenv().getOrDefault("OVERLAY", DEFAULT_OVERLAY);
        run(overlay);
    }

    /**
     * Main logic, separated from {@code main()} for testability.
     *
     * @param overlay the overlay name
     * @return the collected resource rows
     */
    static List<ResourceRow> run(String overlay) {
        Path repoRoot = ScriptUtils.findRepoRoot();

        System.out.println("=== Resource breakdown (overlay: " + overlay + ") ===");
        System.out.println();

        List<Map<String, Object>> baseDocs =
                ScriptUtils.runKustomize(repoRoot, "overlays/" + overlay + "/base", true);
        List<Map<String, Object>> stackDocs =
                ScriptUtils.runKustomize(repoRoot, "overlays/" + overlay + "/stack", true);

        List<ResourceRow> rows = collectAllRows(baseDocs, stackDocs);

        printTable(rows);
        printSummary(rows);

        return rows;
    }

    // --- Row collection ---

    /**
     * Collect resource rows from all Deployments and custom resources.
     */
    static List<ResourceRow> collectAllRows(List<Map<String, Object>> baseDocs,
                                             List<Map<String, Object>> stackDocs) {
        List<ResourceRow> rows = new ArrayList<>();
        List<Map<String, Object>> allDocs = new ArrayList<>(baseDocs);
        allDocs.addAll(stackDocs);

        rows.addAll(collectDeploymentRows(allDocs));

        Map<String, List<String>> crdResourcePaths = buildCrdResourcePaths(baseDocs);
        rows.addAll(collectCrRows(stackDocs, crdResourcePaths));

        return rows;
    }

    /**
     * Collect one row per container in each Deployment.
     */
    @SuppressWarnings("unchecked")
    static List<ResourceRow> collectDeploymentRows(List<Map<String, Object>> docs) {
        List<ResourceRow> rows = new ArrayList<>();

        List<Map<String, Object>> deployments = docs.stream()
                .filter(doc -> "Deployment".equals(doc.get("kind")))
                .collect(Collectors.toList());

        for (Map<String, Object> deployment : deployments) {
            ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(deployment);
            Map<String, Object> templateSpec =
                    CrdSchemaUtils.getNestedMap(deployment, "spec", "template", "spec");
            if (templateSpec == null) continue;

            Object containersObj = templateSpec.get("containers");
            if (!(containersObj instanceof List)) continue;

            for (Object item : (List<?>) containersObj) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> container = (Map<String, Object>) item;
                String containerName = (String) container.getOrDefault("name", "<unknown>");

                long[] values = extractRequestValues(container.get("resources"));
                rows.add(new ResourceRow("Deployment", ref.namespace, ref.name,
                        containerName, values[0], values[1]));
            }
        }
        return rows;
    }

    /**
     * Walk CRD schemas to discover ResourceRequirements paths.
     */
    static Map<String, List<String>> buildCrdResourcePaths(List<Map<String, Object>> baseDocs) {
        Map<String, List<String>> crdResourcePaths = new LinkedHashMap<>();

        List<Map<String, Object>> crds = baseDocs.stream()
                .filter(doc -> "CustomResourceDefinition".equals(doc.get("kind")))
                .collect(Collectors.toList());

        for (Map<String, Object> crd : crds) {
            String kind = CrdSchemaUtils.extractCrdKind(crd);
            if (kind == null) continue;

            Map<String, Object> schema = CrdSchemaUtils.extractCrdSchema(crd);
            if (schema == null) continue;

            Map<String, Object> specSchema =
                    CrdSchemaUtils.getNestedMap(schema, "properties", "spec");
            if (specSchema == null) continue;

            List<String> paths = new ArrayList<>();
            CrdSchemaUtils.walkSchema(specSchema, ".spec", paths);

            paths = paths.stream()
                    .filter(p -> !CrdSchemaUtils.isPodSpecOverheadPath(p))
                    .collect(Collectors.toList());

            if (!paths.isEmpty()) {
                crdResourcePaths.put(kind, paths);
            }
        }
        return crdResourcePaths;
    }

    /**
     * Collect one row per resolved ResourceRequirements path in each CR.
     * Skips unconfigured optional features.
     */
    @SuppressWarnings("unchecked")
    static List<ResourceRow> collectCrRows(List<Map<String, Object>> stackDocs,
                                            Map<String, List<String>> crdResourcePaths) {
        List<ResourceRow> rows = new ArrayList<>();

        for (Map<String, Object> doc : stackDocs) {
            String kind = (String) doc.get("kind");
            if (kind == null || !crdResourcePaths.containsKey(kind)) continue;

            ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(doc);
            List<String> paths = crdResourcePaths.get(kind);

            for (String path : paths) {
                String[] segments = path.substring(1).split("\\.");
                if (segments.length == 0) continue;

                String resourcesKey = segments[segments.length - 1];
                String[] parentSegments = new String[segments.length - 1];
                System.arraycopy(segments, 0, parentSegments, 0, segments.length - 1);

                List<CrdSchemaUtils.ResolvedNode> parents =
                        CrdSchemaUtils.resolvePath(doc, parentSegments, 0, "");

                if (parents.isEmpty()) continue;

                for (CrdSchemaUtils.ResolvedNode parent : parents) {
                    if (!(parent.value instanceof Map)) continue;
                    Map<String, Object> parentMap = (Map<String, Object>) parent.value;
                    Object resourcesObj = parentMap.get(resourcesKey);

                    long[] values = extractRequestValues(resourcesObj);
                    String resolvedPath = parent.path + "." + resourcesKey;
                    rows.add(new ResourceRow(kind, ref.namespace, ref.name,
                            resolvedPath, values[0], values[1]));
                }
            }
        }
        return rows;
    }

    // --- Value extraction ---

    /**
     * Extract CPU (millicores) and memory (MiB) request values from a
     * resources object.
     *
     * @return {@code long[]{cpuMillis, memoryMiB}}
     */
    @SuppressWarnings("unchecked")
    private static long[] extractRequestValues(Object resourcesObj) {
        long cpu = 0, memory = 0;
        if (resourcesObj instanceof Map) {
            Map<String, Object> resources = (Map<String, Object>) resourcesObj;
            Object requestsObj = resources.get("requests");
            if (requestsObj instanceof Map) {
                Map<String, Object> requests = (Map<String, Object>) requestsObj;
                if (requests.containsKey("cpu"))
                    cpu = CrdSchemaUtils.parseCpuMillis(requests.get("cpu"));
                if (requests.containsKey("memory"))
                    memory = CrdSchemaUtils.parseMemoryMiB(requests.get("memory"));
            }
        }
        return new long[]{cpu, memory};
    }

    // --- Output ---

    /**
     * Print a column-aligned resource table.
     */
    static void printTable(List<ResourceRow> rows) {
        List<ResourceRow> deployments = rows.stream()
                .filter(r -> "Deployment".equals(r.type))
                .collect(Collectors.toList());

        List<ResourceRow> crs = rows.stream()
                .filter(r -> !"Deployment".equals(r.type))
                .collect(Collectors.toList());

        if (!deployments.isEmpty()) {
            System.out.println("--- Deployments ---");
            int nsWidth = columnWidth(deployments, r -> r.namespace, "NAMESPACE");
            int nameWidth = columnWidth(deployments, r -> r.name, "NAME");
            int detailWidth = columnWidth(deployments, r -> r.detail, "CONTAINER");

            String fmt = "  %-" + nsWidth + "s  %-" + nameWidth + "s  %-" + detailWidth + "s  %6s  %10s%n";
            System.out.printf(fmt, "NAMESPACE", "NAME", "CONTAINER", "CPU(m)", "MEMORY(Mi)");

            for (ResourceRow row : deployments) {
                System.out.printf(fmt, row.namespace, row.name, row.detail,
                        row.cpuMillis, row.memoryMiB);
            }
            System.out.println();
        }

        if (!crs.isEmpty()) {
            System.out.println("--- Custom Resources ---");
            int kindWidth = columnWidth(crs, r -> r.type, "KIND");
            int nameWidth = columnWidth(crs, r -> r.name, "NAME");
            int detailWidth = columnWidth(crs, r -> r.detail, "PATH");

            String fmt = "  %-" + kindWidth + "s  %-" + nameWidth + "s  %-" + detailWidth + "s  %6s  %10s%n";
            System.out.printf(fmt, "KIND", "NAME", "PATH", "CPU(m)", "MEMORY(Mi)");

            for (ResourceRow row : crs) {
                System.out.printf(fmt, row.type, row.name, row.detail,
                        row.cpuMillis, row.memoryMiB);
            }
            System.out.println();
        }

        if (rows.isEmpty()) {
            System.out.println("  (no resources found)");
            System.out.println();
        }
    }

    /**
     * Print summary totals and suggested frontmatter values.
     */
    static void printSummary(List<ResourceRow> rows) {
        long totalCpu = rows.stream().mapToLong(r -> r.cpuMillis).sum();
        long totalMemory = rows.stream().mapToLong(r -> r.memoryMiB).sum();

        double cpuCores = totalCpu / 1000.0;
        double memoryGiB = totalMemory / 1024.0;

        System.out.println("--- Summary ---");
        System.out.printf("  Total: %dm CPU (%.3g cores), %d MiB memory (%.4g GiB)%n",
                totalCpu, cpuCores, totalMemory, memoryGiB);
        System.out.println();

        System.out.println("--- Suggested frontmatter ---");
        System.out.println("  cpu_total = '" + suggestCpuFrontmatter(totalCpu) + "'");
        System.out.println("  memory_total = '" + suggestMemoryFrontmatter(totalMemory) + "'");
    }

    // --- Frontmatter suggestion ---

    /**
     * Suggest a human-friendly {@code cpu_total} value by rounding up
     * to the next whole number of cores.
     */
    static String suggestCpuFrontmatter(long totalMillis) {
        long cores = (long) Math.ceil(totalMillis / 1000.0);
        if (cores == 1) {
            return "1 CPU core";
        }
        return cores + " CPU cores";
    }

    /**
     * Suggest a human-friendly {@code memory_total} value by rounding
     * up to the next 0.5 GiB increment.
     */
    static String suggestMemoryFrontmatter(long totalMiB) {
        double gib = totalMiB / 1024.0;
        double rounded = Math.ceil(gib * 2.0) / 2.0;
        if (rounded == Math.floor(rounded)) {
            return (long) rounded + " GiB";
        }
        return String.format("%.1f GiB", rounded);
    }

    // --- Utilities ---

    private static int columnWidth(List<ResourceRow> rows,
                                    java.util.function.Function<ResourceRow, String> extractor,
                                    String header) {
        int max = header.length();
        for (ResourceRow row : rows) {
            max = Math.max(max, extractor.apply(row).length());
        }
        return max;
    }

    // --- Data classes ---

    /** A single row in the resource breakdown table. */
    static class ResourceRow {
        final String type;
        final String namespace;
        final String name;
        final String detail;
        final long cpuMillis;
        final long memoryMiB;

        ResourceRow(String type, String namespace, String name,
                    String detail, long cpuMillis, long memoryMiB) {
            this.type = type;
            this.namespace = namespace;
            this.name = name;
            this.detail = detail;
            this.cpuMillis = cpuMillis;
            this.memoryMiB = memoryMiB;
        }
    }
}
