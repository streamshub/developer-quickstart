#!/usr/bin/env bash
#
# Preview the documentation locally using Hugo.
#
# Usage:
#   ./docs/preview.sh          # start a local Hugo server on port 1313
#   ./docs/preview.sh build    # generate static HTML in .docs-preview/public
#
# Requirements:
#   - hugo (https://gohugo.io/installation/)
#   - git  (to fetch the hugo-book theme)
#   - go   (for Hugo module mounts)
#
set -euo pipefail

DOCS_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "${DOCS_DIR}/.." && pwd)"
SITE_DIR="${REPO_DIR}/.docs-preview"
THEME_REPO="https://github.com/alex-shpak/hugo-book.git"
# To update the theme, change THEME_REF to the desired commit SHA from
# https://github.com/alex-shpak/hugo-book/commits/main
# The script detects mismatches and re-fetches automatically.
# This commit matches the version used by the streamshub-site.
THEME_REF="9d6ad30e9e44077846ece81cdd9e59122fccf4af"
THEME_DIR="${SITE_DIR}/themes/hugo-book"

## Check prerequisites ##

for cmd in hugo git go; do
    if ! command -v "${cmd}" &>/dev/null; then
        echo "Error: Required command '${cmd}' is not installed."
        exit 1
    fi
done

## Set up site structure ##

mkdir -p "${SITE_DIR}"

## Initialise Go module (required for Hugo mounts) ##

if [ ! -f "${SITE_DIR}/go.mod" ]; then
    (cd "${SITE_DIR}" && go mod init docs-preview)
fi

## Fetch theme (cached across runs) ##

fetch_theme() {
    echo "Fetching hugo-book theme at ${THEME_REF}..."
    rm -rf "${THEME_DIR}"
    mkdir -p "${THEME_DIR}"
    git -C "${THEME_DIR}" init -q
    git -C "${THEME_DIR}" remote add origin "${THEME_REPO}"
    git -C "${THEME_DIR}" fetch --depth 1 origin "${THEME_REF}"
    git -C "${THEME_DIR}" checkout -q FETCH_HEAD
}

if [ ! -d "${THEME_DIR}/.git" ]; then
    fetch_theme
elif ! git -C "${THEME_DIR}" cat-file -e "${THEME_REF}^{commit}" 2>/dev/null; then
    echo "Cached theme is at a different version; updating..."
    fetch_theme
else
    echo "Using cached hugo-book theme (${THEME_REF:0:12})."
fi

## Generate hugo.toml ##

# Mirrors the relevant settings from the streamshub-site hugo.toml.
# Uses Hugo module mounts to reference the docs directory directly.

cat > "${SITE_DIR}/hugo.toml" << CONFIG
baseURL = 'http://localhost:1313/'
languageCode = 'en-us'
title = 'StreamsHub Developer Quick-Start — Local Preview'
theme = 'hugo-book'

disablePathToLower = true

[markup]
  [markup.tableOfContents]
    startLevel = 1
  [markup.highlight]
    style = "catppuccin-macchiato"
  [markup.goldmark]
    [markup.goldmark.renderer]
      unsafe = true

[params]
  BookTheme = 'dark'
  BookSection = '/'
  BookPortableLinks = true

# Mount the docs directory as content/docs so Hugo reads it directly.
[[module.mounts]]
  source = '${DOCS_DIR}'
  target = 'content/docs'
CONFIG

## Run Hugo ##

cd "${SITE_DIR}"

case "${1:-serve}" in
    build)
        echo "Building static site..."
        hugo --gc --minify
        echo "Output: ${SITE_DIR}/public/"
        ;;
    serve|*)
        echo ""
        echo "Starting local preview server..."
        echo "Open http://localhost:1313/docs/ in your browser."
        echo "Press Ctrl+C to stop."
        echo ""
        hugo server --buildDrafts --disableFastRender
        ;;
esac
