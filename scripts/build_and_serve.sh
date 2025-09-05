#!/usr/bin/env bash

set -euo pipefail

# Build RoadWatch APKs for both flavors, timestamp the outputs, and serve locally.
#
# Usage:
#   scripts/build_and_serve.sh [--type debug|release] [--port <port>] [--no-serve] [--serve-only] [--prune] [--clean-serve]
#
# Defaults:
#   --type debug
#   --port 8080

BUILD_TYPE="debug"
PORT=8080
SERVE=1
SERVE_ONLY=0
PRUNE=0
CLEAN_SERVE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --type)
      BUILD_TYPE="${2:-}"
      shift 2
      ;;
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --no-serve)
      SERVE=0
      shift 1
      ;;
    --serve-only)
      SERVE_ONLY=1
      shift 1
      ;;
    --prune)
      PRUNE=1
      shift 1
      ;;
    --clean-serve)
      CLEAN_SERVE=1
      SERVE=1
      shift 1
      ;;
    -h|--help)
      echo "Usage: $0 [--type debug|release] [--port <port>] [--no-serve]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

BUILD_TYPE_LC=$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')
if [[ "$BUILD_TYPE_LC" != "debug" && "$BUILD_TYPE_LC" != "release" ]]; then
  echo "BUILD_TYPE must be debug or release" >&2
  exit 1
fi

if [[ ! -x ./gradlew ]]; then
  echo "gradlew not found at project root. Run from repo root (android/)" >&2
  exit 1
fi

if [[ "$SERVE_ONLY" -eq 0 ]]; then
  TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
  OUT_DIR="releases/${TIMESTAMP}"
  mkdir -p "$OUT_DIR"
  echo "=== Building APKs (${BUILD_TYPE_LC}) ==="
  if [[ "$BUILD_TYPE_LC" == "debug" ]]; then
    ./gradlew assemblePublicDebug assembleAdminDebug
  else
    ./gradlew assemblePublicRelease assembleAdminRelease
  fi

  echo "=== Collecting artifacts ==="
  declare -a FLAVORS=("public" "admin")
  for FLAVOR in "${FLAVORS[@]}"; do
    SRC_DIR="app/build/outputs/apk/${FLAVOR}/${BUILD_TYPE_LC}"
    if [[ -d "$SRC_DIR" ]]; then
      # Copy all APKs from this variant and rename with timestamp
      shopt -s nullglob
      for APK in "$SRC_DIR"/*.apk; do
        BASE_NAME="roadwatch-${FLAVOR}-${BUILD_TYPE_LC}-${TIMESTAMP}.apk"
        cp -f "$APK" "${OUT_DIR}/${BASE_NAME}"
        echo "Copied $(basename "$APK") -> ${OUT_DIR}/${BASE_NAME}"
      done
    else
      echo "Warning: Expected output directory not found: $SRC_DIR" >&2
    fi
  done
  # Optionally prune older releases, keep the newest only
  if [[ "$PRUNE" -eq 1 || "$CLEAN_SERVE" -eq 1 ]]; then
    echo "=== Pruning old releases ==="
    for d in releases/*; do
      [[ "$d" == "$OUT_DIR" ]] && continue
      rm -rf "$d"
    done
  fi

  echo "=== Artifacts ready ==="
  ls -la "$OUT_DIR" || true
else
  # For serve-only, pick the most recent release folder
  LATEST=$(ls -1dt releases/*/ 2>/dev/null | head -n1)
  if [[ -z "$LATEST" ]]; then
    echo "No releases found to serve. Build first."
    exit 1
  fi
  echo "Serve-only mode. Latest: $LATEST"
  if [[ "$PRUNE" -eq 1 || "$CLEAN_SERVE" -eq 1 ]]; then
    echo "=== Pruning old releases ==="
    for d in releases/*/; do
      [[ "$d" == "$LATEST" ]] && continue
      rm -rf "$d"
    done
  fi
fi

if [[ "$SERVE" -eq 1 ]]; then
  echo "=== Serving releases directory ==="
  if [[ "$CLEAN_SERVE" -eq 1 ]]; then
    if [[ "$SERVE_ONLY" -eq 0 ]]; then
      # We just built; serve the new OUT_DIR directly
      echo "Open: http://localhost:${PORT}/"
      echo "Serving only latest: ${OUT_DIR}"
      cd "$OUT_DIR"
    else
      LATEST=$(ls -1dt releases/*/ 2>/dev/null | head -n1)
      echo "Open: http://localhost:${PORT}/"
      echo "Serving only latest: ${LATEST}"
      cd "$LATEST"
    fi
  else
    echo "Open: http://localhost:${PORT}/"
    if [[ -n "${OUT_DIR:-}" ]]; then
      echo "Latest build folder: ${OUT_DIR}"
    fi
    cd releases
  fi
  echo "Press Ctrl+C to stop the server."
  # Use Python's simple HTTP server to serve directory listing
  python3 -m http.server "$PORT"
fi
