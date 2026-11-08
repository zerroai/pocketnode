#!/bin/bash
# Builds everything and copies it to a server that has Docker. Usage: deploy/relay/deploy.sh user@host domain [path/to/app.apk]
# First time on the server, create ~/pocketnode-relay/relay.env (from relay.env.example) and ~/pocketnode-relay/webapp.env (from webapp.env.example).
set -euo pipefail
HOST="${1:?user@host}"; DOMAIN="${2:?domain}"; APK="${3:-}"
cd "$(dirname "$0")/../.."
./gradlew -q :relay:installDist
(cd website && npm install --no-audit --no-fund --silent && npm run build >/dev/null)
ssh "$HOST" 'mkdir -p ~/pocketnode-relay/image ~/pocketnode-relay/site ~/pocketnode-relay/webapp ~/pocketnode-relay/downloads'
rsync -az --delete relay/build/install/relay/ "$HOST:pocketnode-relay/image/"
rsync -az --delete website/out/ "$HOST:pocketnode-relay/site/"
rsync -az --delete webapp/ "$HOST:pocketnode-relay/webapp/"
[ -n "$APK" ] && rsync -az "$APK" "$HOST:pocketnode-relay/downloads/pocketnode.apk"
scp -q deploy/relay/Dockerfile deploy/relay/docker-compose.yml deploy/relay/Caddyfile "$HOST:pocketnode-relay/"
ssh "$HOST" "cd ~/pocketnode-relay && docker build -f Dockerfile -t pocketnode-relay image && RELAY_DOMAIN=$DOMAIN docker compose up -d --build"
echo "Check: curl https://$DOMAIN/healthz   and open https://$DOMAIN/app/"
