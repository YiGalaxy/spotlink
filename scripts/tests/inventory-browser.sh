#!/bin/sh
set -eu
command -v npx >/dev/null
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:38080/
playwright-cli run-code "$(cat /workspace/scripts/tests/inventory-browser.flow.js)"
playwright-cli close
