#!/bin/bash
# Run on the server from cron. Keeps 14 daily copies of the relay database. The copy is still encrypted message data; store it with the same care.
set -euo pipefail
mkdir -p ~/relay-backups
docker run --rm -v pocketnode-relay_relay-data:/data -v ~/relay-backups:/out alpine sh -c 'cp /data/relay.db /out/relay-$(date +%F).db'
ls -1t ~/relay-backups/relay-*.db | tail -n +15 | xargs -r rm -f
