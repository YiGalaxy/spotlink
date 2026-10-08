#!/bin/sh
set -eu
command -v npx >/dev/null
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:38080/
playwright-cli snapshot
playwright-cli run-code "$(cat /workspace/scripts/tests/mall-browser.flow.js)"
playwright-cli snapshot
playwright-cli close
