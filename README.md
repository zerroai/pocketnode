# Pocketnode

A phone-first AI agent. The phone is the node: it runs the assistant, holds your data, and talks to a model you choose.

Status: early. Work in progress for the Vultr Agent Rush hackathon.

- `core/` - pure Kotlin agent runtime (chat, tools, agents, permissions)
- `app/` - Android app
- `relay/` - small service that holds Telegram messages until the phone picks them up
- `deploy/relay/` - Docker Compose + Caddy deployment for the relay
- `packs/` - tools bundled with the app (phone controls, reminders, notes, weather, utilities, math, safety, Telegram)
- `website/` - project site (static Next.js)

Full setup and architecture notes are coming in the next commits.
