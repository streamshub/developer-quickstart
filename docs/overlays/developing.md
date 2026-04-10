+++
title = 'Developing Overlays'
weight = 100
+++

# Developing Overlays

This page covers the requirements for adding or modifying overlays. 
CI enforces various checks on the overlays automatically on every pull request.

## Directory Structure

Every overlay must have both a directory under `overlays/` and a matching documentation page under `docs/overlays/`. 
The directory name and the doc filename (without `.md`) must match exactly.

```
overlays/<name>/
├── base/               # Phase 1: operators and CRDs
│   └── kustomization.yaml
└── stack/              # Phase 2: operands
    └── kustomization.yaml

docs/overlays/<name>.md # Documentation page with resource totals
```

CI automatically discovers overlays from the `overlays/` directory — there is no manual CI matrix to update. 
The `VerifyDocumentedResources` script checks that every overlay directory has a matching doc page.

### Adding a New Overlay — Checklist

1. Create `overlays/<name>/base/kustomization.yaml` and `overlays/<name>/stack/kustomization.yaml`
2. Set resource limits on all Deployment containers and relevant custom resource fields
3. Run `OVERLAY=<name> jbang .github/scripts/ShowOverlayResources.java` to see the per-component breakdown and suggested frontmatter values
4. Create `docs/overlays/<name>.md` with TOML frontmatter containing `cpu_total` and `memory_total` (use the suggested values from step 3)
5. Verify locally with `OVERLAY=<name> jbang .github/scripts/VerifyResourceLimits.java`
6. Verify documentation totals with `jbang .github/scripts/VerifyDocumentedResources.java`

## Resource Limits

Every container in the overlay must have `resources.requests` and `resources.limits` with both `cpu` and `memory` specified. 
Requests must equal limits.

This applies to:

- Deployment containers — patched via the component's `kustomization.yaml`
- Custom resource fields — set in the CR manifest (e.g., `spec.resources`, `spec.app.resources`)

### Optional Features

The CI script discovers resource fields by walking CRD OpenAPI schemas.
If a CRD declares a `ResourceRequirements` field for an optional feature (e.g., `spec.cruiseControl.resources`), but the CR instance does not configure that feature at all, the resource check is skipped — the CI output will show `SKIPPED (not configured)` for these paths.

If the feature **is** configured (i.e., the parent path exists in the CR), resource limits **must** be set on it.
In short: if you use it, you must set limits on it.

To verify locally:

```shell
OVERLAY=core jbang .github/scripts/VerifyResourceLimits.java
```

## Documented Resource Totals

Each overlay must have a documentation page under `docs/overlays/` with the total resource requirements in its TOML frontmatter:

```toml
+++
title = 'My Overlay'
cpu_total = '4 CPU cores'
memory_total = '5 GiB'
+++
```

The `cpu_total` and `memory_total` values must be greater than or equal to the sum of all resource requests in the overlay's `kustomize build` output. 
Use human-friendly round numbers, the CI enforces `>=`, not exact equality.

Render the values in the page body using Hugo's built-in `param` shortcode:

```markdown
## Resource Requirements

This overlay requires at least {{</* param cpu_total */>}} and
{{</* param memory_total */>}} of allocatable cluster resources.
```

To verify locally:

```shell
jbang .github/scripts/VerifyDocumentedResources.java
```

This script auto-discovers all overlay docs with `cpu_total` and `memory_total` frontmatter, sums the actual resources from `kustomize build`, and checks that the documented values are sufficient.

### Calculating Resource Totals

To see the per-component resource breakdown and the suggested `cpu_total` / `memory_total` values for your overlay:

```shell
OVERLAY=core jbang .github/scripts/ShowOverlayResources.java
```

The script shows every Deployment container and custom resource field with its CPU and memory requests, then prints the totals and suggests round-up values suitable for the frontmatter.
This is a helper tool — it does not enforce anything and always exits successfully.
