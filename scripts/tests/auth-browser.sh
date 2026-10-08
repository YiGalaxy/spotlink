#!/bin/sh
set -eu
command -v npx >/dev/null
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:38080/login
playwright-cli snapshot
playwright-cli run-code "$(cat /workspace/scripts/tests/auth-browser.flow.js)"
playwright-cli snapshot
playwright-cli close
