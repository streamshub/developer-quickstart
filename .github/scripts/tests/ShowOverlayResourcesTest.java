///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.junit.jupiter:junit-jupiter:6.0.3
//DEPS org.junit.platform:junit-platform-launcher:6.0.3
//DEPS io.fabric8:kubernetes-model-core:7.6.1
//SOURCES ../ShowOverlayResources.java

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class ShowOverlayResourcesTest {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ShowOverlayResourcesTest.class))
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

    // --- CPU frontmatter suggestion tests ---

    @Test
    void suggestsCpuRoundUpToNextCore() {
        assertEquals("4 CPU cores", ShowOverlayResources.suggestCpuFrontmatter(3100));
    }

    @Test
    void suggestsCpuExactCore() {
        assertEquals("3 CPU cores", ShowOverlayResources.suggestCpuFrontmatter(3000));
    }

    @Test
    void suggestsCpuSingleCore() {
        assertEquals("1 CPU core", ShowOverlayResources.suggestCpuFrontmatter(800));
    }

    @Test
    void suggestsCpuZero() {
        assertEquals("0 CPU cores", ShowOverlayResources.suggestCpuFrontmatter(0));
    }

    // --- Memory frontmatter suggestion tests ---

    @Test
    void suggestsMemoryRoundUpToHalfGiB() {
        // 3456 MiB = 3.375 GiB -> rounds up to 3.5
        assertEquals("3.5 GiB", ShowOverlayResources.suggestMemoryFrontmatter(3456));
    }

    @Test
    void suggestsMemoryExactGiB() {
        // 4096 MiB = 4.0 GiB -> exactly 4
        assertEquals("4 GiB", ShowOverlayResources.suggestMemoryFrontmatter(4096));
    }

    @Test
    void suggestsMemoryHalfGiBBoundary() {
        // 2560 MiB = 2.5 GiB -> exactly 2.5
        assertEquals("2.5 GiB", ShowOverlayResources.suggestMemoryFrontmatter(2560));
    }

    @Test
    void suggestsMemoryRoundsUpFromJustOverHalf() {
        // 2561 MiB = 2.501 GiB -> rounds up to 3.0
        assertEquals("3 GiB", ShowOverlayResources.suggestMemoryFrontmatter(2561));
    }

    @Test
    void suggestsMemoryZero() {
        assertEquals("0 GiB", ShowOverlayResources.suggestMemoryFrontmatter(0));
    }

    // --- Deployment row collection tests ---

    @Test
    void collectsDeploymentRows() {
        Map<String, Object> deployment = testDeployment("test-op", "test-ns",
                Map.of("name", "main", "resources", guaranteedResources("200m", "256Mi")),
                Map.of("name", "sidecar", "resources", guaranteedResources("100m", "128Mi")));

        List<ShowOverlayResources.ResourceRow> rows =
                ShowOverlayResources.collectDeploymentRows(List.of(deployment));

        assertEquals(2, rows.size());

        ShowOverlayResources.ResourceRow main = rows.get(0);
        assertEquals("Deployment", main.type);
        assertEquals("test-ns", main.namespace);
        assertEquals("test-op", main.name);
        assertEquals("main", main.detail);
        assertEquals(200, main.cpuMillis);
        assertEquals(256, main.memoryMiB);

        ShowOverlayResources.ResourceRow sidecar = rows.get(1);
        assertEquals("sidecar", sidecar.detail);
        assertEquals(100, sidecar.cpuMillis);
        assertEquals(128, sidecar.memoryMiB);
    }

    @Test
    void returnsEmptyRowsForDeploymentWithoutTemplateSpec() {
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", Map.of("name", "broken", "namespace", "default"));
        deployment.put("spec", Map.of());

        List<ShowOverlayResources.ResourceRow> rows =
                ShowOverlayResources.collectDeploymentRows(List.of(deployment));

        assertTrue(rows.isEmpty());
    }

    // --- CR row collection tests ---

    @Test
    void collectsCrRows() {
        Map<String, Object> cr = testCr("TestCR", Map.of(
                "resources", guaranteedResources("500m", "1Gi")));

        Map<String, List<String>> paths = Map.of("TestCR", List.of(".spec.resources"));

        List<ShowOverlayResources.ResourceRow> rows =
                ShowOverlayResources.collectCrRows(List.of(cr), paths);

        assertEquals(1, rows.size());
        assertEquals("TestCR", rows.get(0).type);
        assertEquals("my-cr", rows.get(0).name);
        assertEquals(".spec.resources", rows.get(0).detail);
        assertEquals(500, rows.get(0).cpuMillis);
        assertEquals(1024, rows.get(0).memoryMiB);
    }

    @Test
    void collectsCrRowsFromArray() {
        Map<String, Object> cr = testCr("TestCR", Map.of(
                "containers", List.of(
                        Map.of("name", "app", "resources", guaranteedResources("500m", "512Mi")),
                        Map.of("name", "ui", "resources", guaranteedResources("100m", "256Mi")))));

        Map<String, List<String>> paths = Map.of("TestCR", List.of(".spec.containers[].resources"));

        List<ShowOverlayResources.ResourceRow> rows =
                ShowOverlayResources.collectCrRows(List.of(cr), paths);

        assertEquals(2, rows.size());
        assertEquals(500, rows.get(0).cpuMillis);
        assertEquals(512, rows.get(0).memoryMiB);
        assertEquals(100, rows.get(1).cpuMillis);
        assertEquals(256, rows.get(1).memoryMiB);
    }

    @Test
    void skipsUnconfiguredCrPath() {
        Map<String, Object> cr = testCr("TestCR", Map.of("name", "test"));

        Map<String, List<String>> paths = Map.of("TestCR", List.of(".spec.app.resources"));

        List<ShowOverlayResources.ResourceRow> rows =
                ShowOverlayResources.collectCrRows(List.of(cr), paths);

        assertTrue(rows.isEmpty());
    }

    // --- Full pipeline test ---

    @Test
    void collectsAllRowsFromDeploymentsAndCrs() {
        Map<String, Object> crd = testCrd("TestCR", Map.of(
                "resources", resourceRequirementsSchema()));

        Map<String, Object> deployment = testDeployment("op", "ns",
                Map.of("name", "main", "resources", guaranteedResources("200m", "256Mi")));

        Map<String, Object> cr = testCr("TestCR", Map.of(
                "resources", guaranteedResources("500m", "512Mi")));

        List<ShowOverlayResources.ResourceRow> rows =
                ShowOverlayResources.collectAllRows(List.of(crd, deployment), List.of(cr));

        assertEquals(2, rows.size());

        long totalCpu = rows.stream().mapToLong(r -> r.cpuMillis).sum();
        long totalMemory = rows.stream().mapToLong(r -> r.memoryMiB).sum();
        assertEquals(700, totalCpu);
        assertEquals(768, totalMemory);
    }

    @Test
    void buildCrdResourcePathsFiltersPodSpecOverhead() {
        Map<String, Object> crd = testCrd("TestCR", Map.of(
                "resources", resourceRequirementsSchema(),
                "template", Map.of(
                        "properties", Map.of(
                                "spec", Map.of(
                                        "properties", Map.of(
                                                "resources", resourceRequirementsSchema()))))));

        Map<String, List<String>> paths = ShowOverlayResources.buildCrdResourcePaths(List.of(crd));

        assertEquals(1, paths.get("TestCR").size());
        assertEquals(".spec.resources", paths.get("TestCR").get(0));
    }

    // --- Helpers ---

    private static Map<String, Object> guaranteedResources(String cpu, String memory) {
        return Map.of(
                "requests", Map.of("cpu", cpu, "memory", memory),
                "limits", Map.of("cpu", cpu, "memory", memory));
    }

    private static Map<String, Object> testCr(String kind, Map<String, Object> spec) {
        Map<String, Object> cr = new LinkedHashMap<>();
        cr.put("apiVersion", "test/v1");
        cr.put("kind", kind);
        cr.put("metadata", Map.of("name", "my-cr", "namespace", "default"));
        cr.put("spec", spec);
        return cr;
    }

    @SafeVarargs
    private static Map<String, Object> testDeployment(String name, String namespace,
                                                       Map<String, Object>... containers) {
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", Map.of("name", name, "namespace", namespace));
        deployment.put("spec", Map.of(
                "template", Map.of(
                        "spec", Map.of(
                                "containers", List.of(containers)))));
        return deployment;
    }

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
}
