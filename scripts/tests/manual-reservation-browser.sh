#!/bin/sh
set -eu
command -v npx >/dev/null
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:38080/login
playwright-cli run-code "$(cat /workspace/scripts/tests/manual-reservation-browser.flow.js)"
playwright-cli close
