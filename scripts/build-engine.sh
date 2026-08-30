#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec powershell.exe -File "$ROOT/scripts/build-engine.ps1"
