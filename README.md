# Talaria

An Android app for [Hermes Agent](https://github.com/NousResearch/hermes-agent). It signs in to the
Hermes dashboard you already run (`hermes dashboard`), so your chats, profiles, jobs and bots are
the same ones you use on the desktop. Chatting needs nothing else. Notifications need the
[Talaria plugin](https://github.com/dawnforge-lab/talaria-plugin) on your Hermes and a
[Talaria relay](https://github.com/dawnforge-lab/talaria-plugin/tree/main/relay), which lives in the plugin's repository.

Talaria are the winged sandals of Hermes.

**Status:** a personal project, shared as it is. It's used every day against Hermes 0.22, but it
has one maintainer and no promise of support. Issues and pull requests are welcome, and so are
co-maintainers. See [CONTRIBUTING.md](CONTRIBUTING.md).

## What it does

- **Sign-in:** add a dashboard by address, `talaria://connect` setup link or QR code; password or
  browser sign-in, Cloudflare Access tokens, extra headers for reverse proxies, a setup wizard and
  "Repair connection". Several dashboards, and fingerprint or face unlock of the saved password.
- **Chats:** the chat list with search, date sections, filters, pins, stars and read state;
  projects, artifacts and the workspace browser.
- **Talking to Hermes:** streaming replies with thinking and tool rows; approvals, questions and
  password prompts; slash commands, side and delegate agents, model and reasoning switching;
  attachments from files, camera and paste; Markdown with code, tables, LaTeX and Mermaid; images
  and audio in the transcript. A message typed offline waits and sends when the connection is back.
- **Rich cards:** maps, places, stats, charts, forecasts, comparisons, steps, media and contacts
  that Hermes shows with the plugin's `talaria_show` tool, and confirmations it asks for.
- **Bots and group chats:** profiles as bots with one chat each, and group chats between them.
- **Scheduled jobs and the Tasks board:** create, edit, run and follow jobs; the kanban plugin's
  boards and tasks.
- **Settings and capabilities:** Hermes' settings pages, skills, toolsets, keys, MCP connectors,
  plugins, diagnostics and Restart Gateway.
- **Voice:** dictation, read aloud, hands-free voice conversations, and calls from Hermes that
  ring like a phone call.
- **Notifications** (with the plugin and a relay): replies, approvals, questions, failed turns and
  background jobs, end-to-end encrypted. Approvals and questions can be answered from the
  notification, and a running turn shows its progress in an ongoing notification.
- **Phone data** (with the plugin, each kind off until you turn it on): health through Health
  Connect, location and places, calendar, alarms, timers and people, for Hermes to use.
- **Android extras:** share into a chat, Direct Share, links to chats, a home-screen widget,
  launcher shortcuts, a Quick Settings tile, and two panes on wide screens.

Not built: the live voice modes of the iOS app (Gemini Live and similar). Voice is the classic
listen-and-reply kind.

## Getting it

Each [release](https://github.com/dawnforge-lab/talaria/releases) has a signed APK. It does
everything except notifications: push needs an app built with your own Firebase project and a relay
for it, so for notifications build the app yourself with [docs/building.md](docs/building.md),
which walks through both.

## Privacy

- The app talks to your Hermes dashboard directly. Nothing goes to a server of ours.
- Your dashboard password and session are kept encrypted on the phone, with a key in the Android
  Keystore.
- **Notifications** pass through the relay you set up and through Google's Firebase Cloud
  Messaging. Their content is end-to-end encrypted between the plugin on your Hermes and the
  phone: the relay and Google see that something arrived, for which pairing, and its type. The
  relay keeps installation records and pairing credentials, never message text.
- A running turn's progress (its phase, a tool category, counts) goes through the relay without
  that encryption, so it says nothing about what the turn is about.
- **Phone data** (health, location, calendar) is off until you turn each kind on. When on, it is
  sealed for your Hermes and sent to your dashboard, never to the relay.
- **Map cards** use the Google Maps SDK only when the build has a Maps key; without one, places
  open in your maps app instead.

## Project layout

| Module | Holds |
|---|---|
| `app` | Activity, navigation, Hilt entry point, widget, share target, tile |
| `core:model` | Data types shared by every layer |
| `core:net` | Address rules, sign-in, the REST client |
| `core:gateway` | JSON-RPC over the dashboard's `/api/ws` WebSocket: events, server requests, reconnect |
| `core:chat` | The transcript: stored history and live events folded into the same rows; rich cards |
| `core:database` | Room cache: chat lists, chats, the message waiting for the connection |
| `core:data` | Repositories, the plugin's routes, phone data, secret storage |
| `core:push` | Notifications: Firebase, the relay, end-to-end encryption, the live turn notification |
| `core:voice` | Dictation, read aloud, voice conversations, calls |
| `core:designsystem`, `core:ui` | Compose theme and shared components |
| `feature:*` | Screens: connect, sessions, chat, notifications, cron, settings, tasks, bots |
| `testing:fakegateway` | A scripted fake Hermes for tests |

Build configuration lives in `build-logic/` as convention plugins, and every version is in
`gradle/libs.versions.toml`. Stack: Kotlin with Jetpack Compose (Material 3), Hilt, Room,
Navigation Compose and OkHttp. Min SDK 26, target SDK 37.

[docs/hermes-chat-protocol.md](docs/hermes-chat-protocol.md) has notes on Hermes' chat history and
stream events, as the app reads them.

## Credits

Talaria's design follows [Hermes Conduit](https://github.com/kaishi00/hermes-conduit), kaishi00's
iOS app for Hermes, and its plugin started from Conduit's notifier (both MIT). Talaria is a
separate project, not made or supported by the Conduit author.

## License

MIT. See [LICENSE](LICENSE).
