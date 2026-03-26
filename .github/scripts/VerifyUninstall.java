///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.fabric8:kubernetes-client:7.6.1

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.util.ArrayList;
import java.util.List;


/**
 * Verify that all quick-start resources have been removed after uninstall.
 *
 * Uses the fabric8 Kubernetes client to check for any resources labelled
 * with {@code app.kubernetes.io/part-of=streamshub-developer-quickstart}.
 */
public class VerifyUninstall {

    private static final String LABEL_KEY = "app.kubernetes.io/part-of";
    private static final String LABEL_VALUE = "streamshub-developer-quickstart";

    public static void main(String[] args) {
        System.out.println("--- Checking for remaining quick-start resources ---");

        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<HasMetadata> remaining = new ArrayList<>();

            // Check namespaced resource types
            remaining.addAll(client.namespaces()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.apps().deployments().inAnyNamespace()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.services().inAnyNamespace()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.serviceAccounts().inAnyNamespace()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.configMaps().inAnyNamespace()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());

            // Check cluster-scoped resources: CRDs, ClusterRoles, ClusterRoleBindings
            remaining.addAll(client.apiextensions().v1().customResourceDefinitions()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.rbac().clusterRoles()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.rbac().clusterRoleBindings()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());
            remaining.addAll(client.rbac().roleBindings().inAnyNamespace()
                    .withLabel(LABEL_KEY, LABEL_VALUE).list().getItems());

            if (!remaining.isEmpty()) {
                System.err.println("ERROR: Found " + remaining.size() + " remaining resources after uninstall:");
                remaining.forEach(r -> System.err.println("    "
                        + r.getKind() + "/" + r.getMetadata().getName()
                        + (r.getMetadata().getNamespace() != null
                                ? " (ns: " + r.getMetadata().getNamespace() + ")"
                                : "")));
                System.exit(1);
            }

            System.out.println("All quick-start resources successfully removed");
        }
    }
}
