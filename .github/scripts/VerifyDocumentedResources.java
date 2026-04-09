///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.yaml:snakeyaml:2.6
//DEPS org.tomlj:tomlj:1.1.1
//DEPS io.fabric8:kubernetes-model-core:7.6.1
//SOURCES ScriptUtils.java
//SOURCES CrdSchemaUtils.java

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verify that documented resource totals in overlay doc pages match the
 * actual resource requirements computed from {@code kustomize build}.
 *
 * <p>Each overlay doc stores {@code cpu_total} and {@code memory_total}
 * in its TOML frontmatter. This script sums the resource requests from
 * the kustomize output and asserts that documented values are greater
 * than or equal to the actual values.
 *
 * <p>Also verifies the invariant that resource requests equal limits
 * for every container.
 */
public class VerifyDocumentedResources {

    private static final String OVERLAYS_DIR = "overlays";
    private static final String DOCS_DIR = "docs/overlays";
    private static final Pattern CPU_DOC_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+CPU\\s+cores?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY_DOC_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+GiB", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        try {
            int result = run();
            if (result != 0) {
                System.exit(result);
            }
        } catch (UncheckedIOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Main verification logic, separated from {@code main()} for testability.
     *
     * @return 0 on success, 1 on verification failure
     */
    static int run() {
        Path repoRoot = ScriptUtils.findRepoRoot();
        Path overlaysDir = repoRoot.resolve(OVERLAYS_DIR);
        Path docsDir = repoRoot.resolve(DOCS_DIR);

        System.out.println("=== Verifying documented resource totals ===");
        System.out.println();

        // Check that every overlay directory has a matching doc page
        List<String> coverageErrors = verifyAllOverlaysDocumented(overlaysDir, docsDir);
        if (!coverageErrors.isEmpty()) {
            coverageErrors.forEach(e -> System.err.println("ERROR: " + e));
            System.err.println();
            System.err.println("FAILED: " + coverageErrors.size() + " overlay documentation error(s) found");
            return 1;
        }

        List<OverlayDoc> overlayDocs = discoverOverlayDocs(docsDir);
        if (overlayDocs.isEmpty()) {
            System.err.println("ERROR: No overlay docs with cpu_total/memory_total found in " + docsDir);
            return 1;
        }

        List<String> allErrors = new ArrayList<>();

        for (OverlayDoc doc : overlayDocs) {
            System.out.println("--- " + DOCS_DIR + "/" + doc.fileName + " ---");

            // Verify overlay directory exists
            Path overlayDir = repoRoot.resolve("overlays/" + doc.overlayName);
            if (!Files.isDirectory(overlayDir)) {
                allErrors.add(doc.fileName + ": overlay directory 'overlays/" + doc.overlayName + "' not found");
                continue;
            }

            // Parse documented values
            long docCpuMillis;
            long docMemoryMiB;
            try {
                docCpuMillis = parseDocumentedCpu(doc.cpuTotal);
                docMemoryMiB = parseDocumentedMemory(doc.memoryTotal);
            } catch (IllegalArgumentException e) {
                allErrors.add(doc.fileName + ": " + e.getMessage());
                continue;
            }

            System.out.println("  Documented: cpu=" + docCpuMillis + "m, memory=" + docMemoryMiB + "Mi");

            // Build kustomize output
            List<Map<String, Object>> baseDocs =
                    ScriptUtils.runKustomize(repoRoot, "overlays/" + doc.overlayName + "/base", true);
            List<Map<String, Object>> stackDocs =
                    ScriptUtils.runKustomize(repoRoot, "overlays/" + doc.overlayName + "/stack", true);

            // Sum resources
            ResourceTotals totals = sumAllResources(baseDocs, stackDocs);

            System.out.println("  Actual:     cpu=" + totals.cpuMillis + "m, memory=" + totals.memoryMiB + "Mi");

            // Check documented >= actual
            if (docCpuMillis < totals.cpuMillis) {
                allErrors.add(doc.fileName + ": documented cpu (" + docCpuMillis
                        + "m) is less than actual (" + totals.cpuMillis + "m)");
                System.out.println("  cpu: " + docCpuMillis + "m < " + totals.cpuMillis + "m - FAILED");
            } else {
                System.out.println("  cpu: " + docCpuMillis + "m >= " + totals.cpuMillis + "m - OK");
            }

            if (docMemoryMiB < totals.memoryMiB) {
                allErrors.add(doc.fileName + ": documented memory (" + docMemoryMiB
                        + "Mi) is less than actual (" + totals.memoryMiB + "Mi)");
                System.out.println("  memory: " + docMemoryMiB + "Mi < " + totals.memoryMiB + "Mi - FAILED");
            } else {
                System.out.println("  memory: " + docMemoryMiB + "Mi >= " + totals.memoryMiB + "Mi - OK");
            }

            // Check requests == limits invariant
            if (totals.invariantErrors.isEmpty()) {
                System.out.println("  requests == limits invariant: OK");
            } else {
                allErrors.addAll(totals.invariantErrors);
                System.out.println("  requests == limits invariant: FAILED ("
                        + totals.invariantErrors.size() + " violation(s))");
            }

            System.out.println();
        }

        if (allErrors.isEmpty()) {
            System.out.println("All documented resource totals verified successfully");
            return 0;
        } else {
            allErrors.forEach(e -> System.err.println("ERROR: " + e));
            System.err.println();
            System.err.println("FAILED: " + allErrors.size() + " error(s) found");
            return 1;
        }
    }

    // --- Overlay doc discovery ---

    /**
     * Scan the docs/overlays directory for .md files that have
     * cpu_total and memory_total in their TOML frontmatter.
     */
    static List<OverlayDoc> discoverOverlayDocs(Path docsDir) {
        List<OverlayDoc> results = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(docsDir, "*.md")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (fileName.startsWith("_")) continue;

                String content = Files.readString(file);
                TomlParseResult toml = extractTomlFrontmatter(content);
                if (toml == null) continue;

                String cpuTotal = toml.getString("cpu_total");
                String memoryTotal = toml.getString("memory_total");
                if (cpuTotal == null || memoryTotal == null) continue;

                String overlayName = fileName.replace(".md", "");
                results.add(new OverlayDoc(fileName, overlayName, cpuTotal, memoryTotal));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + docsDir, e);
        }
        return results;
    }

    // --- Overlay documentation coverage ---

    /**
     * Verify that every overlay directory has a corresponding documentation
     * page with {@code cpu_total} and {@code memory_total} in its TOML
     * frontmatter.
     *
     * @param overlaysDir the overlays directory (e.g., {@code overlays/})
     * @param docsDir     the overlay docs directory (e.g., {@code docs/overlays/})
     * @return list of error messages (empty if all overlays are documented)
     */
    static List<String> verifyAllOverlaysDocumented(Path overlaysDir, Path docsDir) {
        List<String> errors = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(overlaysDir, Files::isDirectory)) {
            for (Path overlayDir : stream) {
                String overlayName = overlayDir.getFileName().toString();
                if (overlayName.startsWith(".")) continue;

                Path docFile = docsDir.resolve(overlayName + ".md");
                if (!Files.exists(docFile)) {
                    errors.add("overlay '" + overlayName + "' has no documentation page at "
                            + DOCS_DIR + "/" + overlayName + ".md");
                    continue;
                }

                String content = Files.readString(docFile);
                TomlParseResult toml = extractTomlFrontmatter(content);
                if (toml == null) {
                    errors.add(overlayName + ".md: missing TOML frontmatter (+++...+++ block)");
                    continue;
                }

                if (toml.getString("cpu_total") == null) {
                    errors.add(overlayName + ".md: missing 'cpu_total' in frontmatter");
                }
                if (toml.getString("memory_total") == null) {
                    errors.add(overlayName + ".md: missing 'memory_total' in frontmatter");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + overlaysDir, e);
        }

        if (errors.isEmpty()) {
            System.out.println("All overlays have documentation pages with resource totals");
        }

        return errors;
    }

    // --- TOML frontmatter extraction ---

    /**
     * Extract and parse the TOML frontmatter from a markdown file.
     * Frontmatter is delimited by {@code +++} lines.
     *
     * @return the parsed TOML, or null if no frontmatter found
     */
    static TomlParseResult extractTomlFrontmatter(String content) {
        if (!content.startsWith("+++")) return null;

        int endIndex = content.indexOf("+++", 3);
        if (endIndex < 0) return null;

        String tomlContent = content.substring(3, endIndex).trim();
        return Toml.parse(tomlContent);
    }

    // --- Documented value parsing ---

    /**
     * Parse a documented CPU value like "4 CPU cores" to millicores.
     */
    static long parseDocumentedCpu(String value) {
        Matcher matcher = CPU_DOC_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot parse CPU value: '" + value
                    + "' (expected format: '<number> CPU cores')");
        }
        double cores = Double.parseDouble(matcher.group(1));
        return Math.round(cores * 1000);
    }

    /**
     * Parse a documented memory value like "4.5 GiB" to MiB.
     */
    static long parseDocumentedMemory(String value) {
        Matcher matcher = MEMORY_DOC_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot parse memory value: '" + value
                    + "' (expected format: '<number> GiB')");
        }
        double gib = Double.parseDouble(matcher.group(1));
        return Math.round(gib * 1024);
    }

    // --- Resource summing ---

    /**
     * Sum all resource requests across Deployments (from base) and
     * custom resources (from stack), using CRD schema introspection.
     */
    static ResourceTotals sumAllResources(List<Map<String, Object>> baseDocs,
                                           List<Map<String, Object>> stackDocs) {
        long totalCpu = 0;
        long totalMemory = 0;
        List<String> invariantErrors = new ArrayList<>();

        // --- Deployments (from base and stack) ---
        List<Map<String, Object>> allDocs = new ArrayList<>(baseDocs);
        allDocs.addAll(stackDocs);

        List<Map<String, Object>> deployments = allDocs.stream()
                .filter(doc -> "Deployment".equals(doc.get("kind")))
                .collect(Collectors.toList());

        for (Map<String, Object> deployment : deployments) {
            ResourceTotals dt = sumDeploymentResources(deployment);
            totalCpu += dt.cpuMillis;
            totalMemory += dt.memoryMiB;
            invariantErrors.addAll(dt.invariantErrors);
        }

        // --- CRD schema analysis (from base) ---
        Map<String, List<String>> crdResourcePaths = new LinkedHashMap<>();

        List<Map<String, Object>> crds = baseDocs.stream()
                .filter(doc -> "CustomResourceDefinition".equals(doc.get("kind")))
                .collect(Collectors.toList());

        for (Map<String, Object> crd : crds) {
            String kind = CrdSchemaUtils.extractCrdKind(crd);
            if (kind == null) continue;

            Map<String, Object> schema = CrdSchemaUtils.extractCrdSchema(crd);
            if (schema == null) continue;

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
            }
        }

        // --- CR instances (from stack) ---
        for (Map<String, Object> doc : stackDocs) {
            String kind = (String) doc.get("kind");
            if (kind == null || !crdResourcePaths.containsKey(kind)) continue;

            List<String> paths = crdResourcePaths.get(kind);
            for (String path : paths) {
                ResourceTotals rt = sumCrResourcePath(doc, path);
                totalCpu += rt.cpuMillis;
                totalMemory += rt.memoryMiB;
                invariantErrors.addAll(rt.invariantErrors);
            }
        }

        return new ResourceTotals(totalCpu, totalMemory, invariantErrors);
    }

    /**
     * Sum resource requests from all containers in a Deployment.
     */
    @SuppressWarnings("unchecked")
    static ResourceTotals sumDeploymentResources(Map<String, Object> deployment) {
        long cpu = 0;
        long memory = 0;
        List<String> invariantErrors = new ArrayList<>();

        Map<String, Object> templateSpec = CrdSchemaUtils.getNestedMap(deployment, "spec", "template", "spec");
        if (templateSpec == null) return new ResourceTotals(0, 0, List.of());

        Object containersObj = templateSpec.get("containers");
        if (!(containersObj instanceof List)) return new ResourceTotals(0, 0, List.of());

        ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(deployment);

        for (Object item : (List<?>) containersObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> container = (Map<String, Object>) item;
            String containerName = (String) container.getOrDefault("name", "<unknown>");
            String prefix = ref.namespace + ":" + ref.name + "/" + containerName;

            ResourceTotals rt = extractResourceValues(container.get("resources"), prefix);
            cpu += rt.cpuMillis;
            memory += rt.memoryMiB;
            invariantErrors.addAll(rt.invariantErrors);
        }

        return new ResourceTotals(cpu, memory, invariantErrors);
    }

    /**
     * Sum resource requests from a single ResourceRequirements path in a CR.
     */
    @SuppressWarnings("unchecked")
    static ResourceTotals sumCrResourcePath(Map<String, Object> cr, String path) {
        String[] segments = path.substring(1).split("\\.");
        if (segments.length == 0) return new ResourceTotals(0, 0, List.of());

        String resourcesKey = segments[segments.length - 1];
        String[] parentSegments = new String[segments.length - 1];
        System.arraycopy(segments, 0, parentSegments, 0, segments.length - 1);

        List<CrdSchemaUtils.ResolvedNode> parents = CrdSchemaUtils.resolvePath(cr, parentSegments, 0, "");
        if (parents.isEmpty()) return new ResourceTotals(0, 0, List.of());

        long cpu = 0;
        long memory = 0;
        List<String> invariantErrors = new ArrayList<>();

        ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(cr);

        for (CrdSchemaUtils.ResolvedNode parent : parents) {
            if (!(parent.value instanceof Map)) continue;
            Map<String, Object> parentMap = (Map<String, Object>) parent.value;
            Object resources = parentMap.get(resourcesKey);
            String fullPath = ref.kind + "/" + ref.name + " " + parent.path + "." + resourcesKey;

            ResourceTotals rt = extractResourceValues(resources, fullPath);
            cpu += rt.cpuMillis;
            memory += rt.memoryMiB;
            invariantErrors.addAll(rt.invariantErrors);
        }

        return new ResourceTotals(cpu, memory, invariantErrors);
    }

    /**
     * Extract CPU and memory request values from a resources object,
     * and verify the requests == limits invariant.
     */
    @SuppressWarnings("unchecked")
    static ResourceTotals extractResourceValues(Object resourcesObj, String prefix) {
        if (!(resourcesObj instanceof Map)) return new ResourceTotals(0, 0, List.of());

        Map<String, Object> resources = (Map<String, Object>) resourcesObj;
        long cpu = 0;
        long memory = 0;

        Object requestsObj = resources.get("requests");

        if (requestsObj instanceof Map) {
            Map<String, Object> requests = (Map<String, Object>) requestsObj;
            if (requests.containsKey("cpu")) {
                cpu = CrdSchemaUtils.parseCpuMillis(requests.get("cpu"));
            }
            if (requests.containsKey("memory")) {
                memory = CrdSchemaUtils.parseMemoryMiB(requests.get("memory"));
            }
        }

        List<String> invariantErrors = CrdSchemaUtils.checkRequestsEqualsLimits(resources, prefix);

        return new ResourceTotals(cpu, memory, invariantErrors);
    }

    // --- Data classes ---

    static class OverlayDoc {
        final String fileName;
        final String overlayName;
        final String cpuTotal;
        final String memoryTotal;

        OverlayDoc(String fileName, String overlayName, String cpuTotal, String memoryTotal) {
            this.fileName = fileName;
            this.overlayName = overlayName;
            this.cpuTotal = cpuTotal;
            this.memoryTotal = memoryTotal;
        }
    }

    static class ResourceTotals {
        final long cpuMillis;
        final long memoryMiB;
        final List<String> invariantErrors;

        ResourceTotals(long cpuMillis, long memoryMiB, List<String> invariantErrors) {
            this.cpuMillis = cpuMillis;
            this.memoryMiB = memoryMiB;
            this.invariantErrors = invariantErrors;
        }
    }
}
