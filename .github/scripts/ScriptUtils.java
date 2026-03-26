import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shared utilities for jbang smoke-test scripts.
 *
 * <p>Provides common operations for discovering the repository root,
 * running {@code kubectl kustomize}, parsing manifests, and handling
 * timeouts. Individual scripts include this file via the JBang
 * {@code //SOURCES} directive.
 */
public class ScriptUtils {

    /** Resource kinds that are infrastructure — not custom resources to wait on. */
    static final Set<String> INFRA_KINDS = Set.of(
            "Namespace", "ConfigMap", "Secret", "Service",
            "ServiceAccount", "ClusterRole", "ClusterRoleBinding",
            "Role", "RoleBinding", "Ingress", "NetworkPolicy",
            "ServiceMonitor", "PodMonitor", "KafkaNodePool",
            "ScrapeConfig", "CustomResourceDefinition");

    private static final long KUSTOMIZE_TIMEOUT_SECONDS = 120;

    private ScriptUtils() { }

    /**
     * Find the repository root by walking up from CWD looking for an
     * {@code overlays/} directory.
     *
     * @throws IllegalStateException if no repo root can be determined
     */
    static Path findRepoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path candidate = cwd;
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("overlays"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not find repository root (no 'overlays/' directory found above " + cwd + ")");
    }

    /**
     * Run {@code kubectl kustomize} on all subdirectories of the overlay that
     * contain a {@code kustomization.yaml}, and merge the results.
     *
     * @param repoRoot   the repository root directory
     * @param overlayDir the overlay directory to scan
     * @param strict     if true, exit on failure; if false, log a warning and continue
     */
    static List<Map<String, Object>> kustomizeAllLayers(Path repoRoot, Path overlayDir, boolean strict) {
        List<Map<String, Object>> allDocs = new ArrayList<>();
        try (DirectoryStream<Path> layers = Files.newDirectoryStream(overlayDir, Files::isDirectory)) {
            for (Path layerDir : layers) {
                if (Files.exists(layerDir.resolve("kustomization.yaml"))) {
                    String relativePath = repoRoot.relativize(layerDir).toString();
                    allDocs.addAll(runKustomize(repoRoot, relativePath, strict));
                }
            }
        } catch (Exception e) {
            String msg = "Failed to scan overlay directory: " + e.getMessage();
            if (strict) {
                System.err.println("ERROR: " + msg);
                System.exit(1);
            } else {
                System.err.println("WARNING: " + msg);
            }
        }
        return allDocs;
    }

    /**
     * Run {@code kubectl kustomize} and parse the multi-document YAML output.
     *
     * @param repoRoot the repository root (used as working directory)
     * @param path     the relative path to kustomize
     * @param strict   if true, exit on failure; if false, return empty list
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> runKustomize(Path repoRoot, String path, boolean strict) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "kustomize", path)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(KUSTOMIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String msg = "kubectl kustomize " + path + " timed out after " + KUSTOMIZE_TIMEOUT_SECONDS + "s";
                if (strict) {
                    System.err.println("ERROR: " + msg);
                    System.exit(1);
                } else {
                    System.err.println("WARNING: " + msg);
                    return List.of();
                }
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String msg = "kubectl kustomize " + path + " failed:\n" + output;
                if (strict) {
                    System.err.println("ERROR: " + msg);
                    System.exit(1);
                } else {
                    System.err.println("WARNING: " + msg + " — skipping resource discovery");
                    return List.of();
                }
            }

            Yaml yaml = new Yaml();
            List<Map<String, Object>> docs = new ArrayList<>();
            for (Object doc : yaml.loadAll(output)) {
                if (doc instanceof Map) {
                    docs.add((Map<String, Object>) doc);
                }
            }
            return docs;
        } catch (Exception e) {
            String msg = "Failed to run kubectl kustomize: " + e.getMessage();
            if (strict) {
                System.err.println("ERROR: " + msg);
                System.exit(1);
            } else {
                System.err.println("WARNING: " + msg);
            }
            return List.of();
        }
    }

    /**
     * Parse a timeout string with optional unit suffix.
     *
     * <p>Supported suffixes: {@code s} (seconds, default), {@code m} (minutes),
     * {@code h} (hours). A bare number is treated as seconds.
     *
     * @param timeout the timeout string (e.g. "300s", "5m", "1h", "600")
     * @return the timeout in seconds
     */
    static long parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return 600;
        }
        timeout = timeout.trim();
        long multiplier = 1;
        String numeric = timeout;

        if (timeout.endsWith("h")) {
            multiplier = 3600;
            numeric = timeout.substring(0, timeout.length() - 1);
        } else if (timeout.endsWith("m")) {
            multiplier = 60;
            numeric = timeout.substring(0, timeout.length() - 1);
        } else if (timeout.endsWith("s")) {
            numeric = timeout.substring(0, timeout.length() - 1);
        }

        try {
            return Long.parseLong(numeric.trim()) * multiplier;
        } catch (NumberFormatException e) {
            System.err.println("WARNING: Could not parse timeout '" + timeout + "', defaulting to 600s");
            return 600;
        }
    }

    /**
     * Extract the namespace from a Kubernetes manifest document.
     */
    @SuppressWarnings("unchecked")
    static String extractNamespace(Map<String, Object> doc) {
        Object metadata = doc.get("metadata");
        if (metadata instanceof Map) {
            return (String) ((Map<String, Object>) metadata).get("namespace");
        }
        return null;
    }

    /**
     * A reference to a Kubernetes resource parsed from a kustomize manifest.
     */
    static class ResourceRef {
        final String apiGroupVersion;
        final String kind;
        final String name;
        final String namespace;

        ResourceRef(String apiGroupVersion, String kind, String name, String namespace) {
            this.apiGroupVersion = apiGroupVersion;
            this.kind = kind;
            this.name = name;
            this.namespace = namespace;
        }

        /** Extract the API group from the apiVersion (empty string for core API). */
        String apiGroup() {
            int slash = apiGroupVersion.indexOf('/');
            return slash > 0 ? apiGroupVersion.substring(0, slash) : "";
        }

        @SuppressWarnings("unchecked")
        static ResourceRef fromManifest(Map<String, Object> doc) {
            String apiVersion = (String) doc.getOrDefault("apiVersion", "");
            String kind = (String) doc.getOrDefault("kind", "");
            Map<String, Object> metadata = (Map<String, Object>) doc.getOrDefault("metadata", Map.of());
            String name = (String) metadata.getOrDefault("name", "");
            String namespace = (String) metadata.getOrDefault("namespace", "");
            return new ResourceRef(apiVersion, kind, name, namespace);
        }

        @Override
        public String toString() {
            return namespace + ":" + kind + "/" + name + " (" + apiGroupVersion + ")";
        }
    }
}
