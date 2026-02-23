#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "generate_profiles_from_prebuild.sh now delegates to mined version-specific generation."
"$SCRIPT_DIR/generate_version_specific_profiles.sh" "$@"
