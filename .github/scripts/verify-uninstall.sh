#!/usr/bin/env bash
#
# Verify that all quick-start resources have been removed after uninstall.
#

set -euo pipefail

QUICKSTART_LABEL="app.kubernetes.io/part-of=streamshub-developer-quickstart"

echo "--- Checking for remaining quick-start resources ---"
remaining=$(kubectl get all -A -l "${QUICKSTART_LABEL}" --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [ "$remaining" -gt 0 ]; then
    echo "ERROR: Found $remaining remaining resources after uninstall:"
    kubectl get all -A -l "${QUICKSTART_LABEL}"
    exit 1
fi
echo "All quick-start resources successfully removed"
