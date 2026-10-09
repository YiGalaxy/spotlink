#!/bin/sh
set -eu
command -v npx >/dev/null
engine="${SPOTLINK_BROWSER_ENGINE:-spring-ai}"
case "$engine" in spring-ai|langchain) ;; *) exit 2 ;; esac
playwright-cli --config=/workspace/scripts/tests/browser.config.json open http://localhost:58080/login
playwright-cli snapshot
playwright-cli run-code "$(sed "s/__SPOTLINK_BROWSER_ENGINE__/$engine/g" /workspace/scripts/tests/advisor-early-browser.flow.js)"
playwright-cli close
