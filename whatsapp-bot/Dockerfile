# Bento WhatsApp bot: one container, one Baileys session per organization's WaAccount.
# better-sqlite3 ships N-API prebuilds (prebuilds/linux-<arch>.node) and loads them before any
# compiled build, so install scripts are skipped: its default node-gyp rebuild would need a
# compiler toolchain for nothing. The only other scripts are Baileys' engine check and a
# protobufjs version warning.
FROM node:24-bookworm-slim

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=3000

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund && npm cache clean --force
COPY src ./src

# /data holds the Signal session keys and the spool: owned by the unprivileged node user (1000).
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:3000/healthz').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

# SIGTERM closes every socket cleanly (no half-written credential file) and flushes the spool.
STOPSIGNAL SIGTERM
CMD ["node", "src/index.js"]
