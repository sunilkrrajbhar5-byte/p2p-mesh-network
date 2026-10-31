#!/usr/bin/env bash
set -euo pipefail
# gh authenticates locally. Never publish its token to the site or APK.
gh api --paginate --slurp repos/sunilkrrajbhar5-byte/p2p-mesh-network/releases?per_page=100 \
  --jq '.[][] | select(.draft == false) | .id' |
while read -r release_id; do
  gh api --paginate --slurp "repos/sunilkrrajbhar5-byte/p2p-mesh-network/releases/$release_id/assets?per_page=100" \
    --jq '[.[][] | select(.name == "app-debug.apk" or .name == "SR-Mesh.apk") | .download_count] | add // 0'
done | awk '{total += $1} END {printf "Total APK downloads: %.0f\n", total}'
