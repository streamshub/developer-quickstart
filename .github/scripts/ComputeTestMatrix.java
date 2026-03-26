///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.yaml:snakeyaml:2.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.21.2
//SOURCES ScriptUtils.java

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes the integration test matrix by analysing overlay dependency graphs.
 *
 * <p>Parses each overlay's kustomization.yaml files to extract component sets,
 * then classifies overlays as "leaf" (not covered by any other overlay) or
 * "non-leaf" (a strict subset of at least one other overlay).
 *
 * <p>Leaf overlays are tested on ALL platforms; non-leaf overlays are eliminated.
 *
 * <p>Output: a JSON object suitable for GitHub Actions {@code strategy.matrix.fromJSON()}.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code PLATFORMS} — space-separated list of platforms (default: "minikube kind")</li>
 * </ul>
 */
public class ComputeTestMatrix {

    private static final String DEFAULT_PLATFORMS = "minikube kind";
    private static final String COMPONENTS_PREFIX = "components/";
    private static final String TEST_CONFIG_PATH = ".github/config/test-matrix.yaml";

    public static void main(String[] args) throws IOException {
        String platformsEnv = System.getenv().getOrDefault("PLATFORMS", DEFAULT_PLATFORMS);
        List<String> platforms = Arrays.asList(platformsEnv.trim().split("\\s+"));

        Path repoRoot = ScriptUtils.findRepoRoot();
        Path overlaysDir = repoRoot.resolve("overlays");

        // Step 1: Parse component sets from kustomization files
        Map<String, Set<String>> overlayComponents = parseOverlayComponents(overlaysDir);

        // Step 2: Classify overlays — find leaves
        List<String> leafOverlays = findLeafOverlays(overlayComponents);

        // Step 3: Read per-overlay test config
        Map<String, String> conditionOverrides = readTestConfig(repoRoot);

        // Step 4: Build the matrix JSON
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode includeArray = mapper.createArrayNode();

        for (String overlay : leafOverlays) {
            for (String platform : platforms) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("platform", platform);
                entry.put("overlay", overlay);
                if (conditionOverrides.containsKey(overlay)) {
                    entry.put("condition-overrides", conditionOverrides.get(overlay));
                }
                includeArray.add(entry);
            }
        }

        ObjectNode matrix = mapper.createObjectNode();
        matrix.set("include", includeArray);

        // Output compact JSON to stdout (consumed by GitHub Actions)
        System.out.println(mapper.writeValueAsString(matrix));
    }

    /**
     * Read per-overlay test configuration from the central test config file.
     *
     * @see #TEST_CONFIG_PATH
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> readTestConfig(Path repoRoot) throws IOException {
        Map<String, String> conditionOverrides = new HashMap<>();
        Path configFile = repoRoot.resolve(TEST_CONFIG_PATH);

        if (!Files.exists(configFile)) {
            return conditionOverrides;
        }

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(Files.readString(configFile));
        if (config == null || !config.containsKey("overlays")) {
            return conditionOverrides;
        }

        Map<String, Object> overlays = (Map<String, Object>) config.get("overlays");
        for (Map.Entry<String, Object> entry : overlays.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> overlayConfig = (Map<String, Object>) entry.getValue();
                Object overrides = overlayConfig.get("condition-overrides");
                if (overrides != null) {
                    conditionOverrides.put(entry.getKey(), overrides.toString());
                }
            }
        }

        return conditionOverrides;
    }

    /**
     * Parse the component sets for each overlay.
     *
     * <p>For each overlay directory under overlaysDir, scans all subdirectories
     * for kustomization.yaml files, extracts the {@code components:} list, and
     * normalizes paths to canonical form (e.g. "core/base", "metrics/stack").
     */
    @SuppressWarnings("unchecked")
    static Map<String, Set<String>> parseOverlayComponents(Path overlaysDir) throws IOException {
        Map<String, Set<String>> result = new TreeMap<>();
        Yaml yaml = new Yaml();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(overlaysDir, Files::isDirectory)) {
            for (Path overlayDir : stream) {
                String overlayName = overlayDir.getFileName().toString();
                Set<String> components = new HashSet<>();

                try (DirectoryStream<Path> layers = Files.newDirectoryStream(overlayDir, Files::isDirectory)) {
                    for (Path layerDir : layers) {
                        Path kustomization = layerDir.resolve("kustomization.yaml");
                        if (!Files.exists(kustomization)) {
                            continue;
                        }

                        Map<String, Object> doc = yaml.load(Files.readString(kustomization));
                        if (doc == null || !doc.containsKey("components")) {
                            continue;
                        }

                        List<String> componentPaths = (List<String>) doc.get("components");
                        for (String path : componentPaths) {
                            // Normalize: "../../../components/core/base" → "core/base"
                            int idx = path.indexOf(COMPONENTS_PREFIX);
                            if (idx >= 0) {
                                components.add(path.substring(idx + COMPONENTS_PREFIX.length()));
                            }
                        }
                    }
                }

                if (!components.isEmpty()) {
                    result.put(overlayName, components);
                }
            }
        }

        return result;
    }

    /**
     * Find leaf overlays — those whose component set is NOT a strict subset
     * of any other overlay's component set.
     *
     * <p>An overlay is a "leaf" if no other overlay covers all of its components
     * (and has additional ones). Non-leaf overlays are eliminated from testing
     * because their components are fully exercised by the leaf that covers them.
     */
    static List<String> findLeafOverlays(Map<String, Set<String>> overlayComponents) {
        List<String> leaves = new ArrayList<>();

        for (Map.Entry<String, Set<String>> candidate : overlayComponents.entrySet()) {
            boolean covered = false;

            for (Map.Entry<String, Set<String>> other : overlayComponents.entrySet()) {
                if (other.getKey().equals(candidate.getKey())) {
                    continue;
                }
                // Check if 'other' is a strict superset of 'candidate'
                if (other.getValue().containsAll(candidate.getValue())
                        && other.getValue().size() > candidate.getValue().size()) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                leaves.add(candidate.getKey());
            }
        }

        leaves.sort(String::compareTo);
        return leaves;
    }
}
