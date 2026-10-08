FROM node:22.14.0-alpine
# 浏览器与 CLI 全部运行在容器中；宿主不安装 Node 或 Chromium。
RUN apk add --no-cache chromium font-noto-cjk \
    && npm install --global @playwright/cli@0.1.22
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /workspace/.local/browser
