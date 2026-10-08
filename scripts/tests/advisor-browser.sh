#!/bin/sh
set -eu
command -v npx >/dev/null
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:48080/login
playwright-cli snapshot
playwright-cli run-code "$(cat /workspace/scripts/tests/advisor-browser.flow.js)"
playwright-cli snapshot
playwright-cli run-code "$(cat /workspace/scripts/tests/advisor-chat-browser.flow.js)"
playwright-cli snapshot
playwright-cli close
