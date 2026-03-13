#!/usr/bin/env bash
#
# Update component versions across kustomization files
#
# Usage:
#   ./update-version.sh <component> <new-version>
#   ./update-version.sh --list <component>
#   ./update-version.sh --dry-run <component> <new-version>
#   ./update-version.sh --check <component> <new-version>
#
# Examples:
#   ./update-version.sh strimzi 0.51.0
#   ./update-version.sh apicurio-registry 3.1.8
#   ./update-version.sh streamshub-console 0.12.0
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Color output helpers
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# Component configuration
COMPONENT_LABEL=""
GITHUB_REPO=""
VERSION_REGEX=""
COMPONENT_FILES=()

setup_strimzi() {
    COMPONENT_LABEL="Strimzi"
    GITHUB_REPO="strimzi/strimzi-kafka-operator"
    VERSION_REGEX='releases/download/\K[0-9]+\.[0-9]+\.[0-9]+'
    COMPONENT_FILES=(
        "${SCRIPT_DIR}/base/strimzi-operator/kustomization.yaml"
        "${SCRIPT_DIR}/stack/kafka/kustomization.yaml"
    )
}

setup_apicurio_registry() {
    COMPONENT_LABEL="Apicurio Registry"
    GITHUB_REPO="Apicurio/apicurio-registry"
    VERSION_REGEX='Apicurio/apicurio-registry/\K[0-9]+\.[0-9]+\.[0-9]+'
    COMPONENT_FILES=(
        "${SCRIPT_DIR}/base/apicurio-registry-operator/kustomization.yaml"
    )
}

setup_streamshub_console() {
    COMPONENT_LABEL="StreamsHub Console"
    GITHUB_REPO="streamshub/console"
    VERSION_REGEX='releases/download/\K[0-9]+\.[0-9]+\.[0-9]+'
    COMPONENT_FILES=(
        "${SCRIPT_DIR}/base/streamshub-console-operator/kustomization.yaml"
    )
}

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS] <component> <new-version>

Update component versions in kustomization files.

Components:
  strimzi              Strimzi Kafka Operator
  apicurio-registry    Apicurio Registry Operator
  streamshub-console   StreamsHub Console Operator

Arguments:
  component      The component to update
  new-version    The version to update to (e.g., 0.51.0 or 3.1.8)

Options:
  -c, --check        Only check if the release exists on GitHub (no changes made)
  -d, --dry-run      Show what would be changed without making changes
  -l, --list [N|all] List available versions (default: 20, or 'all')
  -h, --help         Show this help message

Examples:
  $(basename "$0") --list strimzi              # List latest 20 Strimzi versions
  $(basename "$0") --list all strimzi          # List all Strimzi versions
  $(basename "$0") --check strimzi 0.51.0      # Verify release exists
  $(basename "$0") --dry-run strimzi 0.51.0    # Preview changes
  $(basename "$0") strimzi 0.51.0              # Update Strimzi to version
  $(basename "$0") apicurio-registry 3.1.8     # Update Apicurio Registry
  $(basename "$0") streamshub-console 0.12.0   # Update StreamsHub Console
EOF
    exit 0
}

# Set up component configuration based on name
setup_component() {
    local component="$1"
    case "$component" in
        strimzi)
            setup_strimzi
            ;;
        apicurio-registry)
            setup_apicurio_registry
            ;;
        streamshub-console)
            setup_streamshub_console
            ;;
        *)
            error "Unknown component: ${component}"
            error "Valid components: strimzi, apicurio-registry, streamshub-console"
            exit 1
            ;;
    esac
}

# Get current version from the first component file
get_current_version() {
    grep -oP "$VERSION_REGEX" "${COMPONENT_FILES[0]}" | head -1
}

# List available releases from GitHub
list_releases() {
    local limit="${1:-20}"
    local is_all=false
    local per_page="$limit"

    if [[ "$limit" == "all" ]]; then
        is_all=true
        per_page=100
    fi

    info "Fetching available ${COMPONENT_LABEL} releases from GitHub..."
    echo ""

    if ! command -v curl &> /dev/null; then
        error "curl is required to list releases"
        exit 1
    fi

    local releases=""

    if [[ "$is_all" == "true" ]]; then
        local page=1
        while true; do
            local page_releases
            page_releases=$(curl -s "https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=100&page=${page}" \
                | grep '"tag_name":' \
                | sed -E 's/.*"tag_name": "([^"]+)".*/\1/' || true)

            if [[ -z "$page_releases" ]]; then
                break
            fi

            if [[ -n "$releases" ]]; then
                releases="${releases}"$'\n'"${page_releases}"
            else
                releases="$page_releases"
            fi
            ((page++)) || true

            if [[ $page -gt 10 ]]; then
                break
            fi
        done
    else
        releases=$(curl -s "https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=${per_page}" \
            | grep '"tag_name":' \
            | sed -E 's/.*"tag_name": "([^"]+)".*/\1/' \
            | head -n "$limit")
    fi

    if [[ -z "$releases" ]]; then
        error "Failed to fetch releases from GitHub"
        exit 1
    fi

    local current_version
    current_version=$(get_current_version)

    local count
    count=$(echo "$releases" | wc -l | tr -d ' ')

    if [[ "$is_all" == "true" ]]; then
        echo "Available ${COMPONENT_LABEL} versions (all ${count}):"
    else
        echo "Available ${COMPONENT_LABEL} versions (latest ${limit}):"
    fi
    echo ""
    while IFS= read -r version; do
        if [[ "$version" == "$current_version" ]]; then
            echo "  ${version}  (current)"
        else
            echo "  ${version}"
        fi
    done <<< "$releases"
    echo ""
    info "View all releases: https://github.com/${GITHUB_REPO}/releases"
}

# Check if release exists on GitHub
check_release_exists() {
    local version="$1"
    local release_url="https://github.com/${GITHUB_REPO}/releases/tag/${version}"

    info "Checking if ${COMPONENT_LABEL} release ${version} exists..."

    if command -v curl &> /dev/null; then
        local http_code
        http_code=$(curl -s -o /dev/null -w "%{http_code}" -L "$release_url")
        if [[ "$http_code" == "200" ]]; then
            info "Release ${version} exists on GitHub"
            return 0
        else
            error "Release ${version} not found on GitHub (HTTP ${http_code})"
            error "Check available releases at: https://github.com/${GITHUB_REPO}/releases"
            return 1
        fi
    else
        warn "curl not available, skipping release check"
        return 0
    fi
}

# Cross-platform sed -i
sed_inplace() {
    if [[ "$(uname)" == "Darwin" ]]; then
        sed -i '' "$@"
    else
        sed -i "$@"
    fi
}

# Update version in a file
update_file() {
    local file="$1"
    local old_version="$2"
    local new_version="$3"
    local dry_run="$4"

    if [[ ! -f "$file" ]]; then
        warn "File not found: ${file}"
        return 1
    fi

    if ! grep -q "$old_version" "$file"; then
        warn "Version ${old_version} not found in ${file}"
        return 1
    fi

    if [[ "$dry_run" == "true" ]]; then
        echo "Would update: ${file}"
        echo "  Old version: ${old_version}"
        echo "  New version: ${new_version}"
        echo "  Changes:"
        while IFS= read -r line; do
            echo "    - ${line}"
            echo "    + ${line//$old_version/$new_version}"
        done < <(grep "$old_version" "$file")
    else
        sed_inplace "s/${old_version}/${new_version}/g" "$file"
        info "Updated: ${file}"
    fi
}

main() {
    local check_release=false
    local dry_run=false
    local list_releases_flag=false
    local list_limit="20"
    local new_version=""
    local component_arg=""

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -c|--check)
                check_release=true
                shift
                ;;
            -d|--dry-run)
                dry_run=true
                shift
                ;;
            -l|--list)
                list_releases_flag=true
                shift
                if [[ $# -gt 0 && ( "$1" == "all" || "$1" =~ ^[0-9]+$ ) ]]; then
                    list_limit="$1"
                    shift
                fi
                ;;
            -h|--help)
                usage
                ;;
            -*)
                error "Unknown option: $1"
                usage
                ;;
            *)
                if [[ -z "$component_arg" ]]; then
                    component_arg="$1"
                elif [[ -z "$new_version" ]]; then
                    new_version="$1"
                else
                    error "Too many arguments"
                    usage
                fi
                shift
                ;;
        esac
    done

    if [[ -z "$component_arg" ]]; then
        error "No component specified"
        usage
    fi

    setup_component "$component_arg"

    if [[ "$list_releases_flag" == "true" ]]; then
        list_releases "$list_limit"
        exit 0
    fi

    if [[ -z "$new_version" ]]; then
        error "No version specified"
        usage
    fi

    if ! [[ "$new_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        error "Invalid version format: ${new_version}"
        error "Expected format: X.Y.Z (e.g., 0.51.0)"
        exit 1
    fi

    local current_version
    current_version=$(get_current_version)

    if [[ -z "$current_version" ]]; then
        error "Could not determine current ${COMPONENT_LABEL} version"
        exit 1
    fi

    info "Current ${COMPONENT_LABEL} version: ${current_version}"
    info "Target ${COMPONENT_LABEL} version: ${new_version}"

    if [[ "$current_version" == "$new_version" ]]; then
        warn "Already at version ${new_version}"
        exit 0
    fi

    if [[ "$check_release" == "true" ]]; then
        if check_release_exists "$new_version"; then
            exit 0
        else
            exit 1
        fi
    fi

    echo ""

    local files_updated=0
    for file in "${COMPONENT_FILES[@]}"; do
        if update_file "$file" "$current_version" "$new_version" "$dry_run"; then
            ((files_updated++)) || true
        fi
    done

    echo ""

    if [[ "$dry_run" == "true" ]]; then
        info "Dry run complete. ${files_updated} file(s) would be updated."
    else
        info "Updated ${files_updated} file(s) from ${current_version} to ${new_version}"
        echo ""
        info "Next steps:"
        echo "  1. Review the changes: git diff"
        echo "  2. Test the deployment: kubectl kustomize base/ && kubectl kustomize stack/"
        echo "  3. Commit the changes: git add -A && git commit -m 'Update ${COMPONENT_LABEL} to ${new_version}'"
    fi
}

main "$@"
