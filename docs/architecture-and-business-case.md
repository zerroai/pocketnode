# Pocketnode: architecture and business case

## The problem

People ask assistants to build or do things, and then copy the result somewhere else to use it. Agents that run the code they write need a safe place to run it, and most hosted answers are a server that runs model-written code for strangers. That is costly and risky.

## The idea

Run the work where the user is, and keep it locked.

- In the browser, the agent searches, reads pages, opens tabs and writes small programs that run live on the page in a locked frame.
- On an Android phone, the same agent controls the phone: flashlight, alarms, timers, calls, reminders, notes.
- The server only thinks. It makes one model call per step through Vultr Serverless Inference and never runs what the model writes.

## Architecture

```
Browser ──► Caddy (HTTPS) ──┬─► static site and web app UI
                            ├─► webapp  (/api/step, /api/tool, /api/search, /api/read)
                            │      ├─► Vultr Serverless Inference  (deepseek-v4.1-flash-1017, tool calling)
                            │      └─► SearXNG (private, not reachable from outside)
                            ├─► relay   (Telegram inbox, SQLite)
                            └─► /downloads  (Android APK)

Android app ──► Vultr Serverless Inference (direct)
            └─► webapp /api/search and /api/read for web search
```

All services run on one Vultr Cloud Compute VM in London under Docker Compose.

### Browser agent loop

1. The browser holds the conversation and the tool loop (up to 8 steps per message).
2. Each step is one `POST /api/step`. The server checks the login and the daily limit, makes one model call and returns the assistant message with any tool calls.
3. The browser runs each tool. Search and page reading go to `/api/tool` on the server. Opening a tab, speaking, saving a file and running code happen in the browser.
4. Tool results go back into the conversation and the loop continues until the model answers.

### The sandbox (containment)

`run_code` places the model's HTML and JavaScript in `<iframe sandbox="allow-scripts">` with a Content Security Policy of `default-src 'none'`.

| Hostile action | What happens |
|---|---|
| Fetch another website, load a remote image, open a socket | Blocked by the policy. The violation is shown under the frame and sent to the model. |
| Read cookies or the page's data | The frame has its own empty origin, so nothing of the page is reachable. |
| Reach the page around it (`window.parent`) | Throws a security error. |
| Infinite loop | A heartbeat stops. After 4 seconds a watchdog removes the frame and says so. The page keeps working. |
| Delete files (`rm -rf`) | A browser frame has no file system to delete. |

We tested this against the live site with a real browser, including a prompt that asks the model to attack its own sandbox.

### Server safety

- Model output is never executed on the server.
- Page reader: public addresses only, connection pinned to the checked address, redirects re-checked. Loopback, private ranges, cloud metadata addresses, IPv6 loopback, `file:` URLs and URLs with credentials are refused.
- Accounts: scrypt hashes, HttpOnly SameSite cookies, a per-user daily limit, a global daily limit and a per-IP search limit.
- Secrets in mode 600 files on the VM, never in the repository.

### Android app

Kotlin and Jetpack Compose. A pure Kotlin core runs the tool-calling loop against the model. Tools ship as small JavaScript packs in a QuickJS sandbox with a per-pack list of allowed hosts and a 10 second limit per call. The web pack uses the same server search and page reader as the browser app.

## Business use

- **Teams that want an assistant that does things**, not only answers: a customer-facing helper that can build a small calculator, chart or checklist for the person asking, with no code running on company servers.
- **Education and training**: a safe place where learners watch an agent write code and see what the sandbox blocked.
- **Self-hosted privacy**: the app and the web agent run on a VM you control. Chats and files stay in the user's browser or on their phone.

## Cost and scaling

Server cost is the VM plus tokens. Because programs run on the visitor's device, running more code costs the server nothing. Daily per-user and global limits cap token spend. For more users, move the webapp to more VMs behind the same Caddy, since the server keeps no conversation state.

## User rules

In the web app, Settings has a Rules section. The user writes rules the agent must follow (for example "never open more than one tab") and a list of blocked sites. The rules are added to the system prompt on every step. Blocked sites are also enforced by the page itself: it refuses to open or read them whatever the agent asks. The Android app does not have this yet.

## What is not done

- The Android APK on the site is a debug build.
- The Telegram relay is deployed but is not part of the demo.
- Web chats live in the browser only, so they do not sync between devices.
- No licence chosen yet.
