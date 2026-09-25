#!/usr/bin/env bash
# Deploys the WhatsApp bot only, invoked over SSH by the GitHub Actions "bot" job after the
# backend deploy has fast-forwarded this checkout.
#
# The bot has its own image tag and its own script on purpose: restarting it drops every linked
# WhatsApp session for a few seconds, so backend deploys must never do it (deploy.sh runs a plain
# `up -d`, and the bot service lives in the `whatsapp` compose profile that plain `up` ignores).
#
# Environment-agnostic like deploy.sh: the app dir is this script's location, the compose file is
# $DEPLOY_COMPOSE_FILE (default docker-compose.yml), the project is the dir's basename.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${DEPLOY_COMPOSE_FILE:-docker-compose.yml}"
PROJECT="$(basename "$APP_DIR")"
IMAGE_TAG="${1:?usage: deploy-bot.sh <image-tag> (reads GHCR_LOGIN_TOKEN from env)}"

cd "$APP_DIR"
dc() { docker compose -p "$PROJECT" -f "$COMPOSE_FILE" --profile whatsapp "$@"; }

if [ -n "${GHCR_LOGIN_TOKEN:-}" ]; then
  echo "$GHCR_LOGIN_TOKEN" | docker login ghcr.io -u "${GHCR_LOGIN_USER:-github-actions}" --password-stdin
fi

# The bot refuses to start without its secrets; fail the deploy here with a clear message instead.
for var in WHATSAPP_BOT_API_KEY BAILEYS_WEBHOOK_SECRET; do
  if ! grep -Eq "^${var}=.{32,}" .env 2>/dev/null; then
    echo "ERROR: ${var} is not set in ${APP_DIR}/.env (generate with: openssl rand -hex 32)" >&2
    exit 1
  fi
done

# Session keys live here. Owned by the container's node user (1000) and closed to everyone else.
DATA_DIR="$(dc config --format json | python3 -c 'import json,sys; v=json.load(sys.stdin)["services"]["whatsapp-bot"]["volumes"][0]; print(v["source"])')"
if [ ! -d "$DATA_DIR" ]; then
  sudo -n mkdir -p "$DATA_DIR"
fi
sudo -n chown 1000:1000 "$DATA_DIR"
sudo -n chmod 700 "$DATA_DIR"

# Persist the tag so a manual `docker compose --profile whatsapp up -d` keeps this version.
if grep -q '^WHATSAPP_BOT_IMAGE_TAG=' .env; then
  sed -i "s/^WHATSAPP_BOT_IMAGE_TAG=.*/WHATSAPP_BOT_IMAGE_TAG=${IMAGE_TAG}/" .env
else
  printf '\nWHATSAPP_BOT_IMAGE_TAG=%s\n' "$IMAGE_TAG" >> .env
fi
export WHATSAPP_BOT_IMAGE_TAG="$IMAGE_TAG"

dc pull whatsapp-bot
dc up -d --no-deps whatsapp-bot

CONTAINER="$(dc ps -q whatsapp-bot)"
echo "Waiting for $PROJECT/whatsapp-bot ($CONTAINER) to become healthy..."
for i in $(seq 1 60); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)"
  case "$status" in
    healthy)
      echo "$PROJECT/whatsapp-bot healthy after ${i}s"
      exit 0
      ;;
    unhealthy)
      echo "ERROR: whatsapp-bot reported unhealthy" >&2
      docker logs "$CONTAINER" --tail 60 >&2
      exit 1
      ;;
  esac
  sleep 1
done
echo "ERROR: whatsapp-bot did not become healthy within 60s (last status: ${status:-unknown})" >&2
docker logs "$CONTAINER" --tail 60 >&2
exit 1
