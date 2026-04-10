///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.junit.jupiter:junit-jupiter:6.0.3
//DEPS org.junit.platform:junit-platform-launcher:6.0.3
//DEPS io.fabric8:kubernetes-model-core:7.6.1
//SOURCES ../VerifyResourceLimits.java

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

public class VerifyResourceLimitsTest {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(VerifyResourceLimitsTest.class))
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

    // --- CrdSchemaUtils.isResourceRequirements tests ---

    @Test
    void detectsResourceRequirementsSignature() {
        Map<String, Object> properties = Map.of(
                "limits", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("x-kubernetes-int-or-string", true)),
                "requests", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("x-kubernetes-int-or-string", true)));

        assertTrue(CrdSchemaUtils.isResourceRequirements(properties));
    }

    @Test
    void rejectsPropertiesWithoutLimits() {
        Map<String, Object> properties = Map.of(
                "requests", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("x-kubernetes-int-or-string", true)));

        assertFalse(CrdSchemaUtils.isResourceRequirements(properties));
    }

    @Test
    void rejectsPropertiesWithoutRequests() {
        Map<String, Object> properties = Map.of(
                "limits", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("x-kubernetes-int-or-string", true)));

        assertFalse(CrdSchemaUtils.isResourceRequirements(properties));
    }

    @Test
    void rejectsLimitsWithoutIntOrStringMarker() {
        Map<String, Object> properties = Map.of(
                "limits", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("type", "string")),
                "requests", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("x-kubernetes-int-or-string", true)));

        assertFalse(CrdSchemaUtils.isResourceRequirements(properties));
    }

    @Test
    void rejectsRequestsWithoutIntOrStringMarker() {
        Map<String, Object> properties = Map.of(
                "limits", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("x-kubernetes-int-or-string", true)),
                "requests", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("type", "string")));

        assertFalse(CrdSchemaUtils.isResourceRequirements(properties));
    }

    @Test
    void rejectsUnrelatedProperties() {
        Map<String, Object> properties = Map.of(
                "name", Map.of("type", "string"),
                "replicas", Map.of("type", "integer"));

        assertFalse(CrdSchemaUtils.isResourceRequirements(properties));
    }

    // --- CrdSchemaUtils.walkSchema tests ---

    @Test
    void findsDirectResourcesField() {
        // Schema like KafkaNodePool: spec.resources is a ResourceRequirements
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "resources", resourceRequirementsSchema(),
                        "replicas", Map.of("type", "integer")));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(List.of(".spec.resources"), paths);
    }

    @Test
    void findsNestedResourcesField() {
        // Schema like Kafka: spec.entityOperator.topicOperator.resources
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "entityOperator", Map.of(
                                "properties", Map.of(
                                        "topicOperator", Map.of(
                                                "properties", Map.of(
                                                        "resources", resourceRequirementsSchema()))))));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(List.of(".spec.entityOperator.topicOperator.resources"), paths);
    }

    @Test
    void findsResourcesInsideArrayItems() {
        // Schema like PodTemplateSpec: spec.containers[].resources
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "containers", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "properties", Map.of(
                                                "name", Map.of("type", "string"),
                                                "resources", resourceRequirementsSchema())))));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(List.of(".spec.containers[].resources"), paths);
    }

    @Test
    void findsAllResourcesIncludingContainerSiblings() {
        // walkSchema finds ALL ResourceRequirements paths, including resources
        // that are siblings of containers (e.g., Prometheus spec.resources).
        // Filtering is done by callers via isPodSpecOverheadPath.
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

    @Test
    void findsMultiplePaths() {
        // Schema with two ResourceRequirements fields at different levels
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "resources", resourceRequirementsSchema(),
                        "build", Map.of(
                                "properties", Map.of(
                                        "resources", resourceRequirementsSchema()))));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertEquals(2, paths.size());
        assertTrue(paths.contains(".spec.resources"));
        assertTrue(paths.contains(".spec.build.resources"));
    }

    @Test
    void returnsEmptyForSchemaWithoutResources() {
        Map<String, Object> schema = Map.of(
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "config", Map.of(
                                "properties", Map.of(
                                        "logLevel", Map.of("type", "string")))));

        List<String> paths = new ArrayList<>();
        CrdSchemaUtils.walkSchema(schema, ".spec", paths);

        assertTrue(paths.isEmpty());
    }

    // --- CrdSchemaUtils.isPodSpecOverheadPath tests ---

    @Test
    void identifiesPodTemplateSpecOverheadPath() {
        assertTrue(CrdSchemaUtils.isPodSpecOverheadPath(".spec.app.podTemplateSpec.spec.resources"));
        assertTrue(CrdSchemaUtils.isPodSpecOverheadPath(".spec.ui.podTemplateSpec.spec.resources"));
    }

    @Test
    void identifiesTemplateSpecOverheadPath() {
        assertTrue(CrdSchemaUtils.isPodSpecOverheadPath(".spec.template.spec.resources"));
    }

    @Test
    void doesNotFilterCrdLevelResources() {
        // CRD-level resources (e.g., Prometheus spec.resources) are NOT pod overhead
        assertFalse(CrdSchemaUtils.isPodSpecOverheadPath(".spec.resources"));
        assertFalse(CrdSchemaUtils.isPodSpecOverheadPath(".spec.entityOperator.topicOperator.resources"));
        assertFalse(CrdSchemaUtils.isPodSpecOverheadPath(".spec.containers[].resources"));
    }

    // --- checkResourcesObject tests ---

    @Test
    void acceptsCompleteResourcesObject() {
        List<String> errors = VerifyResourceLimits.checkResourcesObject(
                guaranteedResources("500m", "512Mi"), "test");
        assertTrue(errors.isEmpty());
    }

    @Test
    void rejectsMissingResources() {
        List<String> errors = VerifyResourceLimits.checkResourcesObject(null, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing resources"));
    }

    @Test
    void rejectsMissingRequests() {
        Map<String, Object> resources = Map.of(
                "limits", Map.of("cpu", "500m", "memory", "512Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing resources.requests"));
    }

    @Test
    void rejectsMissingLimits() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "500m", "memory", "512Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing resources.limits"));
    }

    @Test
    void rejectsMissingCpuInRequests() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("memory", "512Mi"),
                "limits", Map.of("cpu", "500m", "memory", "512Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing resources.requests.cpu"));
    }

    @Test
    void rejectsMissingMemoryInLimits() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "500m", "memory", "512Mi"),
                "limits", Map.of("cpu", "500m"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing resources.limits.memory"));
    }

    @Test
    void reportsMultipleMissingFields() {
        // Empty requests and limits maps
        Map<String, Object> resources = Map.of(
                "requests", Map.of(),
                "limits", Map.of());

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(4, errors.size());
    }

    // --- requests <= limits invariant tests ---

    @Test
    void passesWhenRequestsBelowLimitsCpu() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "200m", "memory", "256Mi"),
                "limits", Map.of("cpu", "500m", "memory", "256Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void passesWhenRequestsBelowLimitsMemory() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "200m", "memory", "256Mi"),
                "limits", Map.of("cpu", "200m", "memory", "512Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void rejectsRequestsExceedingLimitsCpu() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "500m", "memory", "256Mi"),
                "limits", Map.of("cpu", "200m", "memory", "256Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("requests.cpu"));
    }

    @Test
    void rejectsRequestsExceedingLimitsMemory() {
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "200m", "memory", "512Mi"),
                "limits", Map.of("cpu", "200m", "memory", "256Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("requests.memory"));
    }

    @Test
    void passesWhenRequestsEqualLimits() {
        List<String> errors = VerifyResourceLimits.checkResourcesObject(
                guaranteedResources("200m", "256Mi"), "test");
        assertTrue(errors.isEmpty());
    }

    @Test
    void passesWhenRequestsEqualLimitsInDifferentFormats() {
        // "1" (1 core) and "1000m" (1000 millicores) are semantically equal
        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", "1", "memory", "1Gi"),
                "limits", Map.of("cpu", "1000m", "memory", "1024Mi"));

        List<String> errors = VerifyResourceLimits.checkResourcesObject(resources, "test");
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    // --- CrdSchemaUtils.resolvePath tests ---

    @Test
    void resolvesSimpleMapPath() {
        Map<String, Object> doc = Map.of(
                "spec", Map.of(
                        "resources", Map.of("cpu", "500m")));

        List<CrdSchemaUtils.ResolvedNode> results =
                CrdSchemaUtils.resolvePath(doc, new String[]{"spec", "resources"}, 0, "");

        assertEquals(1, results.size());
        assertEquals(".spec.resources", results.get(0).path);
    }

    @Test
    void resolvesNestedMapPath() {
        Map<String, Object> doc = Map.of(
                "spec", Map.of(
                        "entityOperator", Map.of(
                                "topicOperator", Map.of(
                                        "resources", "found"))));

        List<CrdSchemaUtils.ResolvedNode> results = CrdSchemaUtils.resolvePath(
                doc, new String[]{"spec", "entityOperator", "topicOperator", "resources"}, 0, "");

        assertEquals(1, results.size());
        assertEquals("found", results.get(0).value);
    }

    @Test
    void returnsEmptyForMissingIntermediateKey() {
        Map<String, Object> doc = Map.of("spec", Map.of("name", "test"));

        List<CrdSchemaUtils.ResolvedNode> results = CrdSchemaUtils.resolvePath(
                doc, new String[]{"spec", "entityOperator", "resources"}, 0, "");

        assertTrue(results.isEmpty());
    }

    @Test
    void resolvesArrayPath() {
        Map<String, Object> doc = Map.of(
                "spec", Map.of(
                        "containers", List.of(
                                Map.of("name", "app", "resources", "r1"),
                                Map.of("name", "ui", "resources", "r2"))));

        List<CrdSchemaUtils.ResolvedNode> results = CrdSchemaUtils.resolvePath(
                doc, new String[]{"spec", "containers[]"}, 0, "");

        assertEquals(2, results.size());
        assertEquals(".spec.containers[0]", results.get(0).path);
        assertEquals(".spec.containers[1]", results.get(1).path);
    }

    @Test
    void resolvesFieldInsideArrayElements() {
        Map<String, Object> doc = Map.of(
                "containers", List.of(
                        Map.of("name", "app", "resources", "r1"),
                        Map.of("name", "sidecar", "resources", "r2")));

        List<CrdSchemaUtils.ResolvedNode> results = CrdSchemaUtils.resolvePath(
                doc, new String[]{"containers[]", "resources"}, 0, "");

        assertEquals(2, results.size());
        assertEquals("r1", results.get(0).value);
        assertEquals("r2", results.get(1).value);
    }

    @Test
    void returnsEmptyForMissingArrayKey() {
        Map<String, Object> doc = Map.of("spec", Map.of("name", "test"));

        List<CrdSchemaUtils.ResolvedNode> results = CrdSchemaUtils.resolvePath(
                doc, new String[]{"spec", "containers[]", "resources"}, 0, "");

        assertTrue(results.isEmpty());
    }

    @Test
    void returnsEmptyForEmptyArray() {
        Map<String, Object> doc = Map.of("containers", List.of());

        List<CrdSchemaUtils.ResolvedNode> results = CrdSchemaUtils.resolvePath(
                doc, new String[]{"containers[]", "resources"}, 0, "");

        assertTrue(results.isEmpty());
    }

    // --- validateCrResourcePath tests ---

    @Test
    void validatesPopulatedResourcePath() {
        Map<String, Object> cr = testCr(Map.of(
                "resources", guaranteedResources("500m", "512Mi")));

        List<String> errors = validatePath(cr, ".spec.resources");

        assertTrue(errors.isEmpty());
    }

    @Test
    void skipsUnconfiguredOptionalPath() {
        Map<String, Object> cr = testCr(Map.of("name", "test"));

        List<String> errors = validatePath(cr, ".spec.cruiseControl.resources");

        // Parent "cruiseControl" doesn't exist — should be skipped, not an error
        assertTrue(errors.isEmpty());
    }

    @Test
    void reportsErrorWhenParentExistsButResourcesMissing() {
        Map<String, Object> cr = testCr(Map.of(
                "entityOperator", Map.of(
                        "topicOperator", Map.of(
                                "watchedNamespace", "my-ns"))));

        List<String> errors = validatePath(cr, ".spec.entityOperator.topicOperator.resources");

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing resources"));
    }

    @Test
    void validatesResourcesInsideArrayElements() {
        Map<String, Object> cr = testCr(Map.of(
                "podTemplateSpec", Map.of(
                        "spec", Map.of(
                                "containers", List.of(
                                        Map.of("name", "app",
                                                "resources", guaranteedResources("500m", "512Mi")))))));

        List<String> errors = validatePath(cr, ".spec.podTemplateSpec.spec.containers[].resources");

        assertTrue(errors.isEmpty());
    }

    @Test
    void reportsErrorForIncompleteResourcesInArray() {
        Map<String, Object> cr = testCr(Map.of(
                "containers", List.of(
                        Map.of("name", "app",
                                "resources", Map.of(
                                        "requests", Map.of("cpu", "500m"))))));

        List<String> errors = validatePath(cr, ".spec.containers[].resources");

        assertFalse(errors.isEmpty());
    }

    // --- CrdSchemaUtils.extractCrdKind tests ---

    @Test
    void extractsKindFromCrd() {
        Map<String, Object> crd = Map.of(
                "spec", Map.of(
                        "names", Map.of("kind", "Kafka", "plural", "kafkas")));

        assertEquals("Kafka", CrdSchemaUtils.extractCrdKind(crd));
    }

    @Test
    void returnsNullForCrdWithoutNames() {
        Map<String, Object> crd = Map.of("spec", Map.of());
        assertNull(CrdSchemaUtils.extractCrdKind(crd));
    }

    // --- Deployment checking tests ---

    @Test
    void acceptsDeploymentWithCompleteResources() {
        Map<String, Object> deployment = testDeployment("my-operator",
                Map.of("name", "operator", "resources", guaranteedResources("200m", "256Mi")));

        List<String> errors = VerifyResourceLimits.checkDeploymentResources(deployment);
        assertTrue(errors.isEmpty());
    }

    @Test
    void reportsDeploymentContainerWithoutResources() {
        Map<String, Object> deployment = testDeployment("my-operator",
                Map.of("name", "operator"));

        List<String> errors = VerifyResourceLimits.checkDeploymentResources(deployment);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("my-operator"));
        assertTrue(errors.get(0).contains("operator"));
        assertTrue(errors.get(0).contains("missing resources"));
    }

    @Test
    void checksAllContainersInDeployment() {
        Map<String, Object> deployment = testDeployment("my-app",
                Map.of("name", "main", "resources", guaranteedResources("200m", "256Mi")),
                Map.of("name", "sidecar"));

        List<String> errors = VerifyResourceLimits.checkDeploymentResources(deployment);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("sidecar"));
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

    /** Validate a CR resource path using the standard test CR ref. */
    private static List<String> validatePath(Map<String, Object> cr, String path) {
        ScriptUtils.ResourceRef ref = ScriptUtils.ResourceRef.fromManifest(cr);
        return VerifyResourceLimits.validateCrResourcePath(cr, ref, path);
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
}
