///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.junit.jupiter:junit-jupiter:6.0.3
//DEPS org.junit.platform:junit-platform-launcher:6.0.3
//SOURCES ComputeTestMatrix.java

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class ComputeTestMatrixTest {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ComputeTestMatrixTest.class))
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

    // --- findLeafOverlays tests ---

    @Test
    void singleOverlayIsAlwaysLeaf() {
        Map<String, Set<String>> components = Map.of(
                "core", Set.of("core/base", "core/stack"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("core"), leaves);
    }

    @Test
    void supersetOverlayCoversSubset() {
        // metrics contains everything core has plus more — core is not a leaf
        Map<String, Set<String>> components = Map.of(
                "core", Set.of("core/base", "core/stack"),
                "metrics", Set.of("core/base", "core/stack", "metrics/base", "metrics/stack"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("metrics"), leaves);
    }

    @Test
    void disjointOverlaysAreBothLeaves() {
        // Two overlays with no overlap — both are leaves
        Map<String, Set<String>> components = Map.of(
                "alpha", Set.of("alpha/base", "alpha/stack"),
                "beta", Set.of("beta/base", "beta/stack"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("alpha", "beta"), leaves);
    }

    @Test
    void partialOverlapMeansBothAreLeaves() {
        // Overlapping but neither is a strict superset of the other
        Map<String, Set<String>> components = Map.of(
                "alpha", Set.of("shared/base", "alpha/stack"),
                "beta", Set.of("shared/base", "beta/stack"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("alpha", "beta"), leaves);
    }

    @Test
    void identicalComponentSetsAreBothLeaves() {
        // Same components — neither is a STRICT superset, so both are leaves
        Map<String, Set<String>> components = Map.of(
                "alpha", Set.of("core/base", "core/stack"),
                "beta", Set.of("core/base", "core/stack"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("alpha", "beta"), leaves);
    }

    @Test
    void deepChainOnlyKeepsLeaf() {
        // core ⊂ metrics ⊂ full — only "full" is a leaf
        Map<String, Set<String>> components = Map.of(
                "core", Set.of("core/base"),
                "metrics", Set.of("core/base", "metrics/base"),
                "full", Set.of("core/base", "metrics/base", "full/base"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("full"), leaves);
    }

    @Test
    void diamondDependencyKeepsBothLeaves() {
        // core ⊂ metrics AND core ⊂ proxy, but metrics ⊄ proxy and proxy ⊄ metrics
        Map<String, Set<String>> components = Map.of(
                "core", Set.of("core/base", "core/stack"),
                "metrics", Set.of("core/base", "core/stack", "metrics/base", "metrics/stack"),
                "proxy", Set.of("core/base", "core/stack", "proxy/base", "proxy/stack"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("metrics", "proxy"), leaves);
    }

    @Test
    void leavesAreSortedAlphabetically() {
        Map<String, Set<String>> components = Map.of(
                "zebra", Set.of("zebra/base"),
                "alpha", Set.of("alpha/base"),
                "middle", Set.of("middle/base"));

        List<String> leaves = ComputeTestMatrix.findLeafOverlays(components);

        assertEquals(List.of("alpha", "middle", "zebra"), leaves);
    }

    // --- parseOverlayComponents tests ---

    @Test
    void parsesComponentsFromSubdirectories(@TempDir Path tempDir) throws IOException {
        // Create overlays/core/base/kustomization.yaml
        Path coreBase = tempDir.resolve("core/base");
        Files.createDirectories(coreBase);
        Files.writeString(coreBase.resolve("kustomization.yaml"),
                "components:\n  - ../../../components/core/base\n");

        // Create overlays/core/stack/kustomization.yaml
        Path coreStack = tempDir.resolve("core/stack");
        Files.createDirectories(coreStack);
        Files.writeString(coreStack.resolve("kustomization.yaml"),
                "components:\n  - ../../../components/core/stack\n");

        Map<String, Set<String>> result = ComputeTestMatrix.parseOverlayComponents(tempDir);

        assertEquals(Map.of("core", Set.of("core/base", "core/stack")), result);
    }

    @Test
    void parsesMultipleOverlays(@TempDir Path tempDir) throws IOException {
        // core overlay
        Path coreBase = tempDir.resolve("core/base");
        Files.createDirectories(coreBase);
        Files.writeString(coreBase.resolve("kustomization.yaml"),
                "components:\n  - ../../../components/core/base\n");

        // metrics overlay with core + metrics components
        Path metricsBase = tempDir.resolve("metrics/base");
        Files.createDirectories(metricsBase);
        Files.writeString(metricsBase.resolve("kustomization.yaml"),
                "components:\n"
                        + "  - ../../../components/core/base\n"
                        + "  - ../../../components/metrics/base\n");

        Map<String, Set<String>> result = ComputeTestMatrix.parseOverlayComponents(tempDir);

        assertEquals(Set.of("core/base"), result.get("core"));
        assertEquals(Set.of("core/base", "metrics/base"), result.get("metrics"));
    }

    @Test
    void skipsDirectoriesWithoutKustomization(@TempDir Path tempDir) throws IOException {
        // Create overlay dir with a subdirectory that has no kustomization.yaml
        Path emptyLayer = tempDir.resolve("empty/base");
        Files.createDirectories(emptyLayer);

        Map<String, Set<String>> result = ComputeTestMatrix.parseOverlayComponents(tempDir);

        assertFalse(result.containsKey("empty"));
    }

    @Test
    void skipsKustomizationWithoutComponents(@TempDir Path tempDir) throws IOException {
        // kustomization.yaml with resources but no components
        Path layer = tempDir.resolve("simple/base");
        Files.createDirectories(layer);
        Files.writeString(layer.resolve("kustomization.yaml"),
                "resources:\n  - namespace.yaml\n");

        Map<String, Set<String>> result = ComputeTestMatrix.parseOverlayComponents(tempDir);

        assertFalse(result.containsKey("simple"));
    }

    @Test
    void scansNonStandardLayerNames(@TempDir Path tempDir) throws IOException {
        // An overlay with a "proxy" layer instead of the standard base/stack
        Path proxyLayer = tempDir.resolve("custom/proxy");
        Files.createDirectories(proxyLayer);
        Files.writeString(proxyLayer.resolve("kustomization.yaml"),
                "components:\n  - ../../../components/proxy/config\n");

        Map<String, Set<String>> result = ComputeTestMatrix.parseOverlayComponents(tempDir);

        assertEquals(Map.of("custom", Set.of("proxy/config")), result);
    }

    @Test
    void mergesComponentsAcrossMultipleLayers(@TempDir Path tempDir) throws IOException {
        // Three layers in one overlay
        for (String layer : List.of("base", "stack", "extras")) {
            Path dir = tempDir.resolve("full/" + layer);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("kustomization.yaml"),
                    "components:\n  - ../../../components/full/" + layer + "\n");
        }

        Map<String, Set<String>> result = ComputeTestMatrix.parseOverlayComponents(tempDir);

        assertEquals(Set.of("full/base", "full/stack", "full/extras"), result.get("full"));
    }
}
