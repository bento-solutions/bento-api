#!/usr/bin/env bash
# Deploy script for crm-backend, invoked over SSH by the GitHub Actions deploy key.
# Fast-forwards the checkout (so compose changes and this script stay in sync),
# pulls the image tag GHCR just pushed, recreates changed services, then waits on
# the container's own healthcheck. Non-zero exit fails the Actions job.
#
# Environment-agnostic: the same committed script runs unchanged in the prod app
# dir (/srv/bento/apps/crm-backend, checkout on `main`, docker-compose.yml) and in
# the dev app dir (/srv/bento/apps/crm-backend-dev, checkout on `dev`,
# docker-compose.dev.yml). Everything environment-specific is derived at runtime:
#   - APP_DIR       : this script's own location
#   - branch        : whatever the checkout is on, pulled from origin
#   - compose file  : $DEPLOY_COMPOSE_FILE (default docker-compose.yml)
#   - compose project: basename of APP_DIR -- equals Compose's own default, so the
#                      prod stack keeps its existing project/container/volume names
#   - app container : looked up via `docker compose ps -q app`, never hard-coded
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${DEPLOY_COMPOSE_FILE:-docker-compose.yml}"
PROJECT="$(basename "$APP_DIR")"
IMAGE_TAG="${1:?usage: deploy.sh <image-tag> (reads GHCR_LOGIN_TOKEN from env)}"

cd "$APP_DIR"

dc() { docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"; }

# Keep the compose file and this script current. --ff-only fails loudly rather
# than clobbering anything edited by hand on the server. The branch is whichever
# one this checkout tracks (main for prod, dev for the dev environment).
#
# bento-backend is a PUBLIC repo, so the pull needs no credentials.
#   - http.version=HTTP/1.1: the box's git 2.43 / curl multiplexes info/refs and
#     git-upload-pack onto one HTTP/2 connection, and GitHub 401s the POST --
#     "could not read Username for 'https://github.com'" on a public repo. Pinning
#     HTTP/1.1 for the transfer avoids it. (Also set globally on the server.)
#   - GIT_TERMINAL_PROMPT=0: fail fast instead of hanging on a username prompt.
#   - cleared credential.helper / github.com extraheader: ignore any stale token
#     cached on the box that would turn an anonymous 200 into a 401.
# Do NOT reuse this pattern if the repo is ever made private.
git_pub() {
  GIT_TERMINAL_PROMPT=0 git \
    -c http.version=HTTP/1.1 \
    -c credential.helper= \
    -c 'http.https://github.com/.extraheader=' \
    "$@"
}
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git_pub fetch --quiet origin "$BRANCH"
git_pub merge --ff-only --quiet "origin/$BRANCH"

if [ -n "${GHCR_LOGIN_TOKEN:-}" ]; then
  echo "$GHCR_LOGIN_TOKEN" | docker login ghcr.io -u "${GHCR_LOGIN_USER:-github-actions}" --password-stdin
fi

export BACKEND_IMAGE_TAG="$IMAGE_TAG"
if ! dc pull app; then
  echo "Pull failed (likely transient containerd layer conflict or corrupted cache). Pruning image cache and retrying..."
  docker image prune -f || true
  sleep 3
  dc pull app
fi

# Full `up -d` so committed changes to postgres/redis/pgadmin are applied too.
# Compose only recreates services whose config or image actually changed, so a
# normal app-only deploy leaves the database and cache untouched.
dc up -d

CONTAINER="$(dc ps -q app)"
if [ -z "$CONTAINER" ]; then
  echo "ERROR: could not resolve the 'app' container for project '$PROJECT'" >&2
  dc ps >&2
  exit 1
fi

echo "Waiting for $PROJECT/app ($CONTAINER) to become healthy..."
for i in $(seq 1 90); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)"
  case "$status" in
    healthy)
      echo "$PROJECT/app healthy after ${i}s"
      docker image prune -f >/dev/null 2>&1 || true
      exit 0
      ;;
    unhealthy)
      echo "ERROR: $PROJECT/app reported unhealthy" >&2
      docker logs "$CONTAINER" --tail 80 >&2
      exit 1
      ;;
  esac
  sleep 1
done

echo "ERROR: $PROJECT/app did not become healthy within 90s (last status: ${status:-unknown})" >&2
docker logs "$CONTAINER" --tail 80 >&2
exit 1
