#!/bin/sh
set -eu
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:58080/login
playwright-cli run-code "$(cat /workspace/scripts/tests/advisor-early-browser.flow.js)"
playwright-cli close
