#!/bin/bash
# Builds the relay and copies it to a server that has Docker. Usage: deploy/relay/deploy.sh user@host relay.example.com
# First time on the server: create ~/pocketnode-relay/relay.env from relay.env.example. Push wake-ups (Firebase) are optional.
set -euo pipefail
HOST="${1:?user@host}"; DOMAIN="${2:?domain}"
cd "$(dirname "$0")/../.."
./gradlew -q :relay:installDist
ssh "$HOST" 'mkdir -p ~/pocketnode-relay/image'
rsync -az --delete relay/build/install/relay/ "$HOST:pocketnode-relay/image/"
scp -q deploy/relay/Dockerfile deploy/relay/docker-compose.yml deploy/relay/Caddyfile "$HOST:pocketnode-relay/"
ssh "$HOST" "cd ~/pocketnode-relay && docker build -f Dockerfile -t pocketnode-relay image && RELAY_DOMAIN=$DOMAIN docker compose up -d"
echo "Check: curl https://$DOMAIN/healthz"
