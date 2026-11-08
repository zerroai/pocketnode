# Pocketnode

An AI assistant that works where you are: in an Android app that controls your phone, and in a browser agent that searches the web, opens tabs and writes and runs small programs in a locked frame. Every model call goes to Vultr Serverless Inference.

Built for the Vultr Agent Rush hackathon.

- Live demo: https://108.61.221.17.sslip.io (web app at `/app/`, Android APK at `/downloads/pocketnode.apk`)
- Model: `deepseek-v4.1-flash-1017` on `https://api.vultrinference.com/v1`, with native tool calling

## What is in the repo

| Folder | What it is |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose). Chats, files, packs, agents and canvas. |
| `core/` | Pure Kotlin agent runtime: tool calling loop, permissions, agents. |
| `packs/` | Tools bundled with the app, as plain JavaScript run in a QuickJS sandbox: phone controls, reminders, notes, weather, utilities, math, safety, web search. |
| `relay/` | Small Kotlin service (SQLite) that holds Telegram messages until the phone picks them up. |
| `webapp/` | The browser app's backend: accounts, model steps, search, safe page reader. Python standard library only. |
| `website/` | Landing site (static Next.js export) and the web app UI at `website/public/app/index.html`. |
| `deploy/relay/` | Docker Compose, Caddy and the one-command deploy script for the Vultr VM. |

## Architecture

```
Browser ──► Caddy (auto TLS) ──► /app, /            static site
                              ├─► /api/step, /api/tool, /api/search, /api/read  ─► webapp ─► Vultr Serverless Inference
                              │                                                      └──────► SearXNG (internal only)
                              ├─► /v1, /hook   ─► relay (Telegram inbox)
                              └─► /downloads   Android APK

Android app ──► Vultr Serverless Inference (direct, your key)
            └─► /api/search, /api/read on the server (web pack)
```

How the browser agent works:

1. The browser keeps the conversation and runs the tool loop (at most 8 steps per message).
2. For each step it calls `POST /api/step`. The server makes one model call and returns the reply, including any tool calls. It never executes the model's code.
3. Tools run where they belong. Search and page reading run on the server (`/api/tool`). Opening tabs, speaking, saving files and running code run in the browser.
4. `run_code` puts the model's program in an `<iframe sandbox="allow-scripts">` with a Content Security Policy of `default-src 'none'`. The frame has no network, no cookies, no storage shared with the page and no access to the page around it. Console output, errors and every policy violation are sent back to the page and to the model. A watchdog removes the frame if it stops responding for 4 seconds.

Search order: Tavily (if a key is set), then self-hosted SearXNG, then Wikipedia. The page reader accepts only public addresses, pins the connection to the address it checked and re-checks every redirect, so it cannot be pointed at the server's own network.

## Run it yourself

You need a Vultr Cloud Compute VM with Docker, a Vultr Serverless Inference key, and a domain (an `sslip.io` name for the VM's IP works).

```bash
# on the VM, once
mkdir -p ~/pocketnode-relay && cd ~/pocketnode-relay
# copy deploy/relay/{relay,webapp,searxng}.env.example to relay.env, webapp.env, searxng.env and fill them in
# (VULTR_INFERENCE_KEY, RELAY_STORAGE_KEY, SEARXNG_SECRET; TAVILY_API_KEY is optional)
chmod 600 *.env

# from your computer
deploy/relay/deploy.sh root@<vm-ip> <vm-ip>.sslip.io [path/to/pocketnode.apk]
```

The script builds the relay and the site, copies everything to the VM and starts Docker Compose (relay, webapp, SearXNG, Caddy). Then open `https://<domain>/app/`.

Build the Android app:

```bash
./gradlew :app:assembleDebug -PrelayUrl=https://<domain>
```

On the first screen choose the Vultr preset and paste your Vultr Serverless Inference key. The key stays on the phone.

Longer write-up: [docs/architecture-and-business-case.md](docs/architecture-and-business-case.md).

## Security notes

- The model's output is never run by the server. Programs run only in the visitor's own browser, in the locked frame.
- Accounts use scrypt password hashes and HttpOnly, SameSite cookies. There are per-user daily limits, a global daily limit and a per-IP limit on search.
- Secrets live in `*.env` files on the server (mode 600) and are never committed.
- The APK linked from the site is a debug build.

## Status and limits

- The Android app needs Android 7 or newer. It was tested on a Samsung Galaxy S9.
- Web app chats, files and agents are stored in the browser, not on the server.
- The Telegram relay is included but is not part of the hackathon demo.
- No licence has been chosen yet.
