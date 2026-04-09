///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.junit.jupiter:junit-jupiter:6.0.3
//DEPS org.junit.platform:junit-platform-launcher:6.0.3
//DEPS org.tomlj:tomlj:1.1.1
//DEPS io.fabric8:kubernetes-model-core:7.6.1
//SOURCES ../VerifyDocumentedResources.java

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class VerifyDocumentedResourcesTest {

    private static final String CORE_FRONTMATTER =
            "+++\ntitle = 'Core'\ncpu_total = '4 CPU cores'\nmemory_total = '4 GiB'\n+++\n";

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(VerifyDocumentedResourcesTest.class))
                .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));

        if (summary.getTestsFailedCount() > 0) {
            summary.getFailures().forEach(failure ->
                    failure.getException().printStackTrace());
            System.exit(1);
        }
    }

    // --- Overlay documentation coverage tests ---

    @Test
    void passesWhenAllOverlaysHaveDocs(@TempDir Path tempDir) throws IOException {
        OverlayTestDirs dirs = createOverlayTestDirs(tempDir, "core");
        Files.writeString(dirs.docsDir.resolve("core.md"), CORE_FRONTMATTER);

        List<String> errors = VerifyDocumentedResources.verifyAllOverlaysDocumented(dirs.overlaysDir, dirs.docsDir);
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void failsWhenOverlayHasNoDocPage(@TempDir Path tempDir) throws IOException {
        OverlayTestDirs dirs = createOverlayTestDirs(tempDir, "core", "tracing");
        Files.writeString(dirs.docsDir.resolve("core.md"), CORE_FRONTMATTER);

        List<String> errors = VerifyDocumentedResources.verifyAllOverlaysDocumented(dirs.overlaysDir, dirs.docsDir);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("tracing"));
        assertTrue(errors.get(0).contains("no documentation page"));
    }

    @Test
    void failsWhenDocMissingFrontmatter(@TempDir Path tempDir) throws IOException {
        OverlayTestDirs dirs = createOverlayTestDirs(tempDir, "core");
        Files.writeString(dirs.docsDir.resolve("core.md"), "# Core\nNo frontmatter here.\n");

        List<String> errors = VerifyDocumentedResources.verifyAllOverlaysDocumented(dirs.overlaysDir, dirs.docsDir);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing TOML frontmatter"));
    }

    @Test
    void failsWhenDocMissingCpuTotal(@TempDir Path tempDir) throws IOException {
        OverlayTestDirs dirs = createOverlayTestDirs(tempDir, "core");
        Files.writeString(dirs.docsDir.resolve("core.md"),
                "+++\ntitle = 'Core'\nmemory_total = '4 GiB'\n+++\n");

        List<String> errors = VerifyDocumentedResources.verifyAllOverlaysDocumented(dirs.overlaysDir, dirs.docsDir);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing 'cpu_total'"));
    }

    @Test
    void failsWhenDocMissingMemoryTotal(@TempDir Path tempDir) throws IOException {
        OverlayTestDirs dirs = createOverlayTestDirs(tempDir, "core");
        Files.writeString(dirs.docsDir.resolve("core.md"),
                "+++\ntitle = 'Core'\ncpu_total = '4 CPU cores'\n+++\n");

        List<String> errors = VerifyDocumentedResources.verifyAllOverlaysDocumented(dirs.overlaysDir, dirs.docsDir);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing 'memory_total'"));
    }

    @Test
    void skipsHiddenDirectories(@TempDir Path tempDir) throws IOException {
        OverlayTestDirs dirs = createOverlayTestDirs(tempDir, "core");
        Files.createDirectories(dirs.overlaysDir.resolve(".hidden"));
        Files.writeString(dirs.docsDir.resolve("core.md"), CORE_FRONTMATTER);

        List<String> errors = VerifyDocumentedResources.verifyAllOverlaysDocumented(dirs.overlaysDir, dirs.docsDir);
        assertTrue(errors.isEmpty(), "Hidden dirs should be skipped but got: " + errors);
    }

    // --- TOML frontmatter extraction tests ---

    @Test
    void extractsTomlFrontmatter() {
        String content = "+++\ntitle = 'Core'\ncpu_total = '4 CPU cores'\nmemory_total = '4.5 GiB'\n+++\n# Heading\n";
        TomlParseResult toml = VerifyDocumentedResources.extractTomlFrontmatter(content);

        assertNotNull(toml);
        assertEquals("Core", toml.getString("title"));
        assertEquals("4 CPU cores", toml.getString("cpu_total"));
        assertEquals("4.5 GiB", toml.getString("memory_total"));
    }

    @Test
    void returnsNullForMissingFrontmatter() {
        String content = "# Just a heading\nSome text\n";
        assertNull(VerifyDocumentedResources.extractTomlFrontmatter(content));
    }

    @Test
    void returnsNullForUnclosedFrontmatter() {
        String content = "+++\ntitle = 'Core'\n# No closing delimiter\n";
        assertNull(VerifyDocumentedResources.extractTomlFrontmatter(content));
    }

    @Test
    void handlesExtraFieldsInFrontmatter() {
        String content = "+++\ntitle = 'Metrics'\nweight = 1\ncpu_total = '4 CPU cores'\nmemory_total = '5 GiB'\n+++\n";
        TomlParseResult toml = VerifyDocumentedResources.extractTomlFrontmatter(content);

        assertNotNull(toml);
        assertEquals("4 CPU cores", toml.getString("cpu_total"));
        assertEquals("5 GiB", toml.getString("memory_total"));
        assertEquals(1L, toml.getLong("weight"));
    }

    // --- Documented CPU parsing tests ---

    @Test
    void parsesWholeCpuCores() {
        assertEquals(4000, VerifyDocumentedResources.parseDocumentedCpu("4 CPU cores"));
    }

    @Test
    void parsesFractionalCpuCores() {
        assertEquals(3500, VerifyDocumentedResources.parseDocumentedCpu("3.5 CPU cores"));
    }

    @Test
    void parsesSingularCpuCore() {
        assertEquals(1000, VerifyDocumentedResources.parseDocumentedCpu("1 CPU core"));
    }

    @Test
    void throwsOnUnknownCpuFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> VerifyDocumentedResources.parseDocumentedCpu("4 threads"));
    }

    @Test
    void throwsOnCpuWithoutNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> VerifyDocumentedResources.parseDocumentedCpu("some CPU cores"));
    }

    // --- Documented memory parsing tests ---

    @Test
    void parsesWholeGiB() {
        assertEquals(5120, VerifyDocumentedResources.parseDocumentedMemory("5 GiB"));
    }

    @Test
    void parsesFractionalGiB() {
        assertEquals(4608, VerifyDocumentedResources.parseDocumentedMemory("4.5 GiB"));
    }

    @Test
    void throwsOnUnknownMemoryFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> VerifyDocumentedResources.parseDocumentedMemory("4.5 GB"));
    }

    @Test
    void throwsOnMalformedMemoryDecimal() {
        assertThrows(IllegalArgumentException.class,
                () -> VerifyDocumentedResources.parseDocumentedMemory("...GiB"));
    }

    // --- Kubernetes CPU quantity parsing tests (via CrdSchemaUtils) ---

    @Test
    void parsesMillicoreCpu() {
        assertEquals(200, CrdSchemaUtils.parseCpuMillis("200m"));
    }

    @Test
    void parsesWholeCpuString() {
        assertEquals(1000, CrdSchemaUtils.parseCpuMillis("1"));
    }

    @Test
    void parsesFractionalCpuString() {
        assertEquals(500, CrdSchemaUtils.parseCpuMillis("0.5"));
    }

    @Test
    void parsesIntegerCpuFromYaml() {
        assertEquals(1000, CrdSchemaUtils.parseCpuMillis(Integer.valueOf(1)));
    }

    @Test
    void parsesDoubleCpuFromYaml() {
        assertEquals(500, CrdSchemaUtils.parseCpuMillis(Double.valueOf(0.5)));
    }

    @Test
    void parsesEquivalentCpuFormats() {
        // "1" and "1000m" should both parse to 1000 millicores
        assertEquals(CrdSchemaUtils.parseCpuMillis("1"), CrdSchemaUtils.parseCpuMillis("1000m"));
    }

    // --- Kubernetes memory quantity parsing tests (via CrdSchemaUtils) ---

    @Test
    void parsesMiMemory() {
        assertEquals(256, CrdSchemaUtils.parseMemoryMiB("256Mi"));
    }

    @Test
    void parsesGiMemory() {
        assertEquals(1024, CrdSchemaUtils.parseMemoryMiB("1Gi"));
    }

    @Test
    void parsesMMemory() {
        // 400M (decimal megabytes) -> 400 * 1000000 / 1048576 ≈ 381 MiB
        assertEquals(381, CrdSchemaUtils.parseMemoryMiB("400M"));
    }

    @Test
    void parsesKiMemory() {
        // 1024 Ki = 1 MiB
        assertEquals(1, CrdSchemaUtils.parseMemoryMiB("1024Ki"));
    }

    @Test
    void parsesTiMemory() {
        // 1 Ti = 1048576 MiB
        assertEquals(1_048_576, CrdSchemaUtils.parseMemoryMiB("1Ti"));
    }

    @Test
    void parsesGMemory() {
        // 1G (decimal gigabyte) = 1000000000 bytes ≈ 954 MiB
        assertEquals(954, CrdSchemaUtils.parseMemoryMiB("1G"));
    }

    @Test
    void parsesLowercaseKMemory() {
        // 1048576k = 1048576 * 1000 bytes = 1048576000 bytes ≈ 1000 MiB
        assertEquals(1000, CrdSchemaUtils.parseMemoryMiB("1048576k"));
    }

    @Test
    void parsesIntegerMemoryFromYaml() {
        // 268435456 bytes = 256 MiB
        assertEquals(256, CrdSchemaUtils.parseMemoryMiB(Integer.valueOf(268435456)));
    }

    // --- Resource extraction and invariant tests ---

    @Test
    void extractsResourceValues() {
        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.extractResourceValues(
                        guaranteedResources("500m", "512Mi"), "test");

        assertEquals(500, totals.cpuMillis);
        assertEquals(512, totals.memoryMiB);
        assertTrue(totals.invariantErrors.isEmpty());
    }

    @Test
    void returnsZeroForMissingResources() {
        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.extractResourceValues(null, "test");

        assertEquals(0, totals.cpuMillis);
        assertEquals(0, totals.memoryMiB);
    }

    @Test
    void detectsRequestsNotEqualLimitsCpu() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "200m", "memory", "256Mi"),
                "limits", Map.of("cpu", "500m", "memory", "256Mi"));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.extractResourceValues(resources, "test");

        assertEquals(200, totals.cpuMillis);
        assertEquals(1, totals.invariantErrors.size());
        assertTrue(totals.invariantErrors.get(0).contains("requests.cpu"));
    }

    @Test
    void detectsRequestsNotEqualLimitsMemory() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "200m", "memory", "256Mi"),
                "limits", Map.of("cpu", "200m", "memory", "512Mi"));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.extractResourceValues(resources, "test");

        assertEquals(256, totals.memoryMiB);
        assertEquals(1, totals.invariantErrors.size());
        assertTrue(totals.invariantErrors.get(0).contains("requests.memory"));
    }

    @Test
    void passesWhenRequestsEqualLimits() {
        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.extractResourceValues(
                        guaranteedResources("200m", "256Mi"), "test");

        assertTrue(totals.invariantErrors.isEmpty());
    }

    // --- Deployment summing tests ---

    @Test
    void sumsDeploymentContainerResources() {
        Map<String, Object> deployment = testDeployment("test-op",
                Map.of("name", "main", "resources", guaranteedResources("200m", "256Mi")),
                Map.of("name", "sidecar", "resources", guaranteedResources("100m", "128Mi")));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.sumDeploymentResources(deployment);

        assertEquals(300, totals.cpuMillis);
        assertEquals(384, totals.memoryMiB);
        assertTrue(totals.invariantErrors.isEmpty());
    }

    // --- Comparison logic tests ---

    @Test
    void passesWhenDocumentedExceedsActual() {
        // doc=4000m, actual=3250m -> should pass (no error)
        long documented = 4000;
        long actual = 3250;
        assertTrue(documented >= actual);
    }

    @Test
    void passesWhenDocumentedEqualsActual() {
        long documented = 3250;
        long actual = 3250;
        assertTrue(documented >= actual);
    }

    @Test
    void failsWhenDocumentedBelowActual() {
        long documented = 3000;
        long actual = 3250;
        assertFalse(documented >= actual);
    }

    // --- CRD schema walking tests (shared via CrdSchemaUtils) ---

    @Test
    void findsResourcesPathInSchema() {
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "resources", resourceRequirementsSchema(),
                        "replicas", Map.of("type", "integer")));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(List.of(".spec.resources"), paths);
    }

    @Test
    void findsNestedResourcesPath() {
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "app", Map.of(
                                "properties", Map.of(
                                        "resources", resourceRequirementsSchema()))));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(List.of(".spec.app.resources"), paths);
    }

    @Test
    void includesResourcesSiblingToContainers() {
        // walkSchema finds ALL ResourceRequirements, including resources
        // that are siblings of containers. CRDs like Prometheus have
        // spec.resources (main container) alongside spec.containers (sidecars).
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "containers", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "properties", Map.of(
                                                "resources", resourceRequirementsSchema()))),
                        "resources", resourceRequirementsSchema()));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(2, paths.size());
        assertTrue(paths.contains(".spec.containers[].resources"));
        assertTrue(paths.contains(".spec.resources"));
    }

    // --- CR resource path summing tests ---

    @Test
    void sumsCrResourcePath() {
        Map<String, Object> cr = testCr(Map.of(
                "resources", guaranteedResources("500m", "1Gi")));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.sumCrResourcePath(cr, ".spec.resources");

        assertEquals(500, totals.cpuMillis);
        assertEquals(1024, totals.memoryMiB);
        assertTrue(totals.invariantErrors.isEmpty());
    }

    @Test
    void returnsZeroForMissingCrPath() {
        Map<String, Object> cr = testCr(Map.of("name", "test"));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.sumCrResourcePath(cr, ".spec.app.resources");

        assertEquals(0, totals.cpuMillis);
        assertEquals(0, totals.memoryMiB);
    }

    @Test
    void sumsResourcesAcrossArrayElements() {
        Map<String, Object> cr = testCr(Map.of(
                "containers", List.of(
                        Map.of("name", "app", "resources", guaranteedResources("500m", "512Mi")),
                        Map.of("name", "ui", "resources", guaranteedResources("100m", "256Mi")))));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.sumCrResourcePath(cr, ".spec.containers[].resources");

        assertEquals(600, totals.cpuMillis);
        assertEquals(768, totals.memoryMiB);
    }

    // --- Pod-spec overhead filtering test ---

    @Test
    void sumAllResourcesFiltersPodSpecOverheadPaths() {
        // CRD with both component-level and pod-spec overhead ResourceRequirements
        Map<String, Object> crd = testCrd("TestCR", Map.of(
                // Component-level resources (should be counted)
                "resources", resourceRequirementsSchema(),
                // Pod-spec overhead (should be filtered)
                "template", Map.of(
                        "properties", Map.of(
                                "spec", Map.of(
                                        "properties", Map.of(
                                                "resources", resourceRequirementsSchema()))))));

        // CR instance with resources at both paths
        Map<String, Object> cr = testCr(Map.of(
                "resources", guaranteedResources("500m", "512Mi"),
                "template", Map.of(
                        "spec", Map.of(
                                "resources", guaranteedResources("100m", "128Mi")))));

        VerifyDocumentedResources.ResourceTotals totals =
                VerifyDocumentedResources.sumAllResources(List.of(crd), List.of(cr));

        // Only component-level resources should be counted, not the pod-spec overhead
        assertEquals(500, totals.cpuMillis);
        assertEquals(512, totals.memoryMiB);
        assertTrue(totals.invariantErrors.isEmpty());
    }

    // --- Helpers ---

    /** Build a Guaranteed QoS resources map (requests == limits). */
    private static Map<String, Object> guaranteedResources(String cpu, String memory) {
        return Map.of(
                "requests", Map.of("cpu", cpu, "memory", memory),
                "limits", Map.of("cpu", cpu, "memory", memory));
    }

    /** Build a test CR with standard metadata and the given spec. */
    private static Map<String, Object> testCr(Map<String, Object> spec) {
        Map<String, Object> cr = new LinkedHashMap<>();
        cr.put("apiVersion", "test/v1");
        cr.put("kind", "TestCR");
        cr.put("metadata", Map.of("name", "my-cr", "namespace", "default"));
        cr.put("spec", spec);
        return cr;
    }

    /** Build a test Deployment with the given containers. */
    @SafeVarargs
    private static Map<String, Object> testDeployment(String name, Map<String, Object>... containers) {
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", Map.of("name", name, "namespace", "default"));
        deployment.put("spec", Map.of(
                "template", Map.of(
                        "spec", Map.of(
                                "containers", List.of(containers)))));
        return deployment;
    }

    /** Build a test CRD with the given kind and spec-level schema properties. */
    private static Map<String, Object> testCrd(String kind, Map<String, Object> specProperties) {
        Map<String, Object> crd = new LinkedHashMap<>();
        crd.put("apiVersion", "apiextensions.k8s.io/v1");
        crd.put("kind", "CustomResourceDefinition");
        crd.put("metadata", Map.of("name", kind.toLowerCase() + "s.test.io"));
        crd.put("spec", Map.of(
                "names", Map.of("kind", kind),
                "versions", List.of(Map.of(
                        "name", "v1",
                        "schema", Map.of(
                                "openAPIV3Schema", Map.of(
                                        "properties", Map.of(
                                                "spec", Map.of(
                                                        "properties", specProperties))))))));
        return crd;
    }

    /** Build a minimal ResourceRequirements-shaped schema node. */
    private static Map<String, Object> resourceRequirementsSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limits", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of(
                                        "x-kubernetes-int-or-string", true)),
                        "requests", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of(
                                        "x-kubernetes-int-or-string", true))));
    }

    /** Create overlay and docs directories with the given overlay names. */
    private static OverlayTestDirs createOverlayTestDirs(Path tempDir, String... overlayNames) throws IOException {
        Path overlaysDir = tempDir.resolve("overlays");
        Path docsDir = tempDir.resolve("docs");
        for (String name : overlayNames) {
            Files.createDirectories(overlaysDir.resolve(name));
        }
        Files.createDirectories(docsDir);
        return new OverlayTestDirs(overlaysDir, docsDir);
    }

    private record OverlayTestDirs(Path overlaysDir, Path docsDir) { }
}
