#!/usr/bin/env bash
# The version the NEXT release will have: one past the newest release tag vMAJOR.MM (7.00 -> 7.01, 7.99 -> 8.00).
# version-major.txt sets the major version; raising it starts at MAJOR.00. Older v0.6.N tags are ignored.
# Prints BMOE_VERSION=7.01 and BMOE_VERSION_CODE=7001 (versionCode = major * 1000 + minor).
set -euo pipefail
BASE=$(tr -dc '0-9' < "$(dirname "$0")/../version-major.txt")
LAST=$(git tag -l 'v*' | sed -n 's/^v\([0-9][0-9]*\)\.\([0-9][0-9]\)$/\1 \2/p' | sort -k1,1n -k2,2n | tail -1)
if [ -n "$LAST" ]; then
  MA=${LAST% *}; MI=$((10#${LAST#* } + 1))
  if [ "$MI" -gt 99 ]; then MA=$((MA + 1)); MI=0; fi
else
  MA=$BASE; MI=0
fi
if [ "$MA" -lt "$BASE" ]; then MA=$BASE; MI=0; fi
printf 'BMOE_VERSION=%d.%02d\nBMOE_VERSION_CODE=%d\n' "$MA" "$MI" $((MA * 1000 + MI))
