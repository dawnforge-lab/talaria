# Hermes 0.22.2: chat history and stream events

Read from the Hermes Agent 0.22.2 source (`hermes_cli/web_routers/sessions.py`,
`hermes_state_messages.py`, `tui_gateway/*`, `agent/*`) and the Desktop client
(`apps/desktop/src/...`), which is the reference consumer. These are the facts `core:chat` relies on.

## Two ids

Events and RPCs use the **runtime** session id (`session.create` / `session.resume` /
`session.activate` return it as `session_id`). REST takes the **stored** id (`session_key`,
`stored_session_id`). REST may answer with a different stored id than the one asked for: the tip of
a compressed conversation.

## History: `GET /api/sessions/{id}/messages`

| Param | Use |
|---|---|
| `profile` | always |
| `order` | always `latest`: `offset` counts back from the newest row, rows still come oldest → newest. Without `order`, `limit=50` gives the *oldest* 50. |
| `limit` | ≤ 500 (clamped); `0` returns nothing |
| `offset` | advance by `pagination.returned` (it counts hidden rows too) |
| `include_compacted` | always `true` (otherwise in-place compacted turns vanish and model-only rows can leak) |
| `inline_images` | `false`: content becomes a string, image parts read `[image]` |

Envelope: `{session_id, profile, messages: [...], pagination: {limit, offset, order, returned}}`.
No total; more pages exist while `returned == limit`. The whole compression lineage is merged.

### Rows

```jsonc
{
  "id": 4711,                       // durable row id (RPCs call it row_id)
  "role": "user" | "assistant" | "tool" | "system",
  "content": "…" | [parts] | null,
  "tool_calls": [{"id":"call_abc","type":"function","function":{"name":"terminal","arguments":"{…json string…}"}}],
  "tool_call_id": "call_abc",       // tool rows
  "tool_name": "terminal",          // tool rows (not `name`)
  "timestamp": 1761050000.123,      // float epoch seconds
  "reasoning": "…", "reasoning_content": "…",
  "display_kind": null | "hidden" | "steer" | "model_switch" | "personality_switch" | "auto_continue"
                | "async_delegation_complete" | "process_complete" | "failed_turn" | "internal_notification",
  "display_metadata": {…} | "<json text on old servers>",   // reactions, interrupted, task_count, display_text, error
  "display_content": "…",           // prefer over content when present (steer text, compaction carriers)
  "display_reasoning": "…"          // prefer over reasoning when present
}
```

- `hidden` rows are model scaffolding: never shown.
- An assistant row's reasoning, text and `tool_calls` come in that order; each call's result is a
  later `role: tool` row with the matching `tool_call_id`.
- `failed_turn` is an assistant row holding Hermes' notice for a failed turn.
- `display_metadata.interrupted` marks a partial reply saved when a turn was stopped.
- User rows carry attachments as `@image:<path>` lines after the caption, `[screenshot]`
  placeholders, and expanded `@file:` context after `\n\n--- Attached Context ---\n\n`.
- Tool failures have no flag: `success: false`, `ok: false`, a non-empty `error`, or a non-zero
  `exit_code` in the result.

## A turn on the socket

Frames: `{"method":"event","params":{"type","session_id","payload"?,"seq"}}`; `seq` is per
runtime session. `message.delta`, `reasoning.delta` and `thinking.delta` are held for up to 33 ms
and sent as a burst, each still its own frame.

1. `prompt.submit {session_id, text}` → `{status: "streaming", user_row_id?}`, or `queued` /
   `steered` / `redirected` when the session is busy.
2. `message.start` (no payload).
3. Per model call, in order: `reasoning.delta {text}` (appends) or `reasoning.available {text}`
   (replaces), `message.delta {text}` (appends), `message.interim {text, already_streamed}` (the
   authoritative text of the segment just finished: replace the streamed text with it), then
   `tool.start` / `tool.complete` per call. `thinking.delta` is status text ("waiting for the
   model…"), not reasoning. The first delta after a tool round starts with `"\n\n"`.
4. `message.complete {text, status: complete|error|interrupted, reasoning?, usage, persisted_turn?,
   response_previewed?, response_reused?, response_transformed?, partial?, error?}`. `text` is only
   the **last** segment. `previewed`/`reused`: nothing new to show. `transformed`: replace the last
   segment. `error` without `partial`: `text` is the error copy.
5. `session.info {running: false, …}`: the authoritative end of the turn. Some turns end with
   `error {message}` and this, without `message.complete`.

`tool.start {tool_id, name, context?, args?}`; `tool.complete {tool_id, name, args, duration_s?,
result, summary?, inline_diff? (ANSI)}`. `tool_id` equals the stored `tool_calls[].id`.

Other events: `session.title {session_id (stored), title}`, `session.usage {usage}` (context meter:
`usage.context_used/max/percent`), `status.update {kind, text}` (`compacting`, …),
`todo.updated`, `subagent.*`, `message.reaction`, `notification.show`.

There is no `message.interrupted`, `session.busy`, `tool.progress` or `context.update` in 0.22.2.

`message.start` can arrive without a `prompt.submit` from this client: a queued prompt draining,
an auto-continue, or a turn sent from another client.

## Opening a chat that may be running

`session.resume {session_id: stored, omit_messages: true, profile}` →
`{session_id: runtime, session_key, running, status, inflight?: {user, assistant}, queued?: {user},
open_requests?, info}`. `inflight.assistant` is all streamed text so far, with no tool or reasoning
boundaries. Load REST history, paint `inflight` as the running turn, then follow live events; after
the turn ends, reload the newest page of history.

## Sending

`prompt.submit {session_id, text, queued?}` → `{status: streaming | queued | steered | redirected,
user_row_id?}`. There is no client message id or dedupe key: a retry after a lost reply can start a
second turn, so check the transcript first (a user row newer than the last one seen whose text
starts with the sent text, or `inflight.user` / `queued.user` on a resume). Storage failures
(5070–5072) mean the message was not saved.

While a turn runs, a plain submit follows Hermes' `display.busy_input_mode`
(`config.get {key: "busy"}` → `interrupt` (default) | `queue` | `steer`). A client can also ask
for each outcome directly:

| Intent | Call | Result |
|---|---|---|
| Correct the running turn | `session.redirect {session_id, text}` | `redirected`; `rejected` on an idle session (send normally) |
| Add to the running turn | `session.steer {session_id, text}` | `queued` (means accepted); `rejected` when idle |
| Send after this turn | keep it in the app, then `prompt.submit {queued: true}` once the turn ends | the server queue cannot be edited or cancelled |
| Stop | `session.interrupt {session_id}` | `interrupted`; clears Hermes' queue, withdraws open requests, the turn ends with `message.complete {status: interrupted}` |

Right after a stop, `prompt.submit` may answer 4009 (busy) for a few seconds: retry with a short
backoff.

## Server requests

`{"id": "srq-…", "method", "params": {session_id, …}}`; the first answer wins for every client, and
a JSON-RPC error other than 4404 settles it as unanswered for all of them. Decline what this client
does not show with 4404. `request.cancel {id, method, reason}` withdraws one (`timeout`,
`interrupted`, `resolved` by another client, `session_closed`).

| Method | Params | Answer | Timeout |
|---|---|---|---|
| `approval` | `command, description, choices ⊆ [once, session, always, deny], smart_denied, tool_name` | `{choice, all?}`; an error is a withdrawal, not a deny | 300 s |
| `clarify` | `questions: [{qid, question, choices ≤ 4, multi_select}]`, `answers?` on replay | `{answers: {qid: text \| JSON array text \| null}}`; `{}` cancels | 3600 s |
| `sudo` | `command` | `{value: password}`; `""` declines | 120 s |
| `secret` | `env_var, prompt` | `{value}`; `""` skips; the value is saved to the profile's env | 300 s |
| `vault.*` | | `{value}` | 120–180 s |

### Answering from a notification (no request in hand)

- **Approval:** `approval.respond {session_id, choice}` on any connection, with `session_id` being
  the chat's stored id (the push's `session_key`).
  - A stored id that isn't a live session is looked up among the live sessions.
  - Without `request_id`, it answers that session's oldest waiting approval.
  - The result `{resolved: n}` is 0 when nothing was waiting any more (answered elsewhere, or timed
    out).
  - It only reaches turns running in the dashboard's own gateway. A one-shot CLI turn
    (`hermes chat -q`) never asks.
- **Question (the `conduit_push` plugin's clarify):**
  `POST <relay>/v1/decisions/<request_id>/respond {answer, question_id?, gateway_id}`, with the
  device credential.
  - `answer` is `e2e1.<kid>.<nonce>.<ciphertext>`, sealed with the pairing's answer key, and its
    associated data names the request and qid.
  - The relay answers 404, 409 or 410 when the question is gone, answered elsewhere, or handed back
    to Hermes.

`approval_mode` (in `session.info`): `manual` asks every time; `smart` lets a guardian model
decide first and asks only when it refuses (`smart_denied`, choices `once`/`deny`); `off` never
asks. It is a global config setting (`approvals.mode`).

## Attachments

Staged per session before `prompt.submit`, which consumes everything staged.

- Images: `image.attach_bytes {session_id, content_base64, filename}` (≤ 25 MiB; PNG, JPEG, GIF,
  WebP, BMP) → `{path, count, width, height}`. Put nothing for them in the text; the stored user
  row gets `@image:<path>` lines.
- Files (PDF, anything): `file.attach {session_id, data_url, name}` → `{path, ref_text}`. Put
  `ref_text` (`@file:<path>`) in the prompt, before the typed text. PDFs are not text-extracted;
  the model is told where the file is and reads it with its tools.

## Model and reasoning of one chat

- **Current:** `session.info` carries `model`, `provider` and `reasoning_effort`.
  - An empty `reasoning_effort` is the provider's default, and `"none"` turns thinking off.
  - `reasoning_effort_wire` is the level actually sent once it has been clamped to what the route
    supports.
  - While a switch waits for the next turn, `model` and `provider` show the pick.
- **Choices:** `model.options {session_id, explicit_only: true}` →
  `{providers: [{slug, name, models[], authenticated, is_current, capabilities: {<model>: {reasoning, can_disable_reasoning?}}, …}], model, provider}`.
  - This is what the desktop app asks for. `GET /api/model/options` is its REST twin.
  - Models don't list the levels they support: Hermes clamps the level itself.
- **Switch the model for this chat only:**
  `config.set {session_id, key: "model", value: "<model> --provider <slug> --session"}` →
  `{value, warning?, deferred?, confirm_required?, confirm_message?, scope}`.
  - `--session` keeps it out of config.yaml.
  - During a turn the pick waits (`deferred: true`) and lands when the next turn starts.
  - `confirm_required` means an expensive model: send again with `confirm_expensive_model: true`.
  - A new `session.info` follows.
- **Reasoning for this chat:** `config.set {session_id, key: "reasoning", value: <level>}`, with no
  `scope`.
  - The levels are `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` and `ultra`.
  - It applies at once, and `session.info` follows.
- **Not** `POST /api/model/set`: it rewrites the profile's default in config.yaml.

## Speech (`/api/audio/…`)

Every call takes `?profile=`.
- **Transcribe:** `POST /api/audio/transcribe {data_url: "data:audio/wav;base64,…", mime_type}` →
  `{ok, transcript, provider}`.
  - Up to 25 MB (413 above). A provider error is a 400 `detail`.
  - No words is `transcript: ""`.
  - It can take minutes: the app waits 3 to 10 minutes, longer for a longer recording.
- **Live transcription:** WebSocket `/api/audio/transcribe-stream?ticket=…&profile=`.
  - The ticket is single use, from `/api/auth/ws-ticket`; a bad one closes 4401 or 4403.
  - The client sends `{"sample_rate": 8000–48000}`, then binary signed 16-bit mono PCM, then
    `{"eos": true}`. A disconnect cancels.
  - The server sends `{type: "partial", text}`, then `{type: "final", transcript, provider}` or
    `{type: "error", message}`.
  - It needs `stt.streaming: true` and a provider that streams (not local Whisper). Otherwise
    the error comes first, and the client uploads the recording.
- **Speak:** `POST /api/audio/speak {text}` → `{ok, data_url, mime_type, provider}`.
- **Live speech:** WebSocket `/api/audio/speak-stream?ticket=…&profile=`.
  - The client sends `{"text"}` frames as the reply grows, then `{"done": true}`. `{"stop": true}`
    or a disconnect cuts it off.
  - The server sends `{type: "start", sample_rate, channels}`, binary 16-bit PCM a sentence at a
    time, then `{type: "end"}`. It sends `{type: "fallback"}` when it made no audio.
  - Markdown is stripped there.
- **A spoken turn:** `prompt.submit` with `surface: "voice-live"` and `voice_turn: true`.
  - Hermes runs it on the `auxiliary.voice_chat` task (`GET /api/model/auxiliary`; set with
    `POST /api/model/set {scope: "auxiliary", task: "voice_chat", provider, model,
    reasoning_effort}`), with reasoning "none" by default.
  - A reasoning model with reasoning off may write its thinking into the answer: give the task
    some reasoning ("low"), or a model that doesn't think.
  - A Hermes without the field refuses it with 4000; send it again without it.
- **What's set up:** `/api/tools/toolsets/{stt|tts}/config` → `providers[{name, is_active,
  status}]`. Speech-to-text is on unless `stt.enabled` is false in `/api/config`.
  - The default text-to-speech, Microsoft Edge, needs no key, but Microsoft refuses it at
    times (403).

## Scheduled jobs (REST, `/api/cron/jobs`)

Every call takes `?profile=`; Hermes also finds a job by id in whichever profile holds it.

- **List:** `GET /api/cron/jobs` → a bare array of jobs. A job is
  `{id, name, prompt, schedule: {kind, expr | minutes | run_at, display}, schedule_display, enabled,
  state, next_run_at, last_run_at, last_status, last_error, deliver, model, provider, no_agent, script, …}`.
  - `state` is `scheduled`, `paused`, `completed` (a one-off that ran) or `error`.
  - Times are ISO strings with an offset. `last_status` is `ok`, `error` or `delivery_failed`.
- **One job:** `GET /api/cron/jobs/{id}`.
- **Create:** `POST /api/cron/jobs {schedule, prompt, name?, deliver}` → the job.
  - `schedule` is required: a cron expression, `every 2h`, `30m` (recurring), `in 30m` or an ISO
    time (one-off), or phrases like `weekdays at 9:30am`.
  - A schedule Hermes can't read is a 400 whose `detail` lists the forms.
  - An empty name is made from the prompt's first 50 characters.
  - `deliver` is where results go, besides the run's own chat. The values:
    - `local`: nowhere else;
    - `origin`: the chat the job was made from, else the first home channel;
    - `<platform>`: that platform's home channel;
    - `<platform>:<chat>`;
    - `bot-chat[:<profile>]`;
    - `all`;
    - a comma-separated mix.
  - The body also takes `skills`, `model`, `provider`, `script`, `enabled_toolsets`, `workdir`
    and `repeat`. The app sends none of these, as iOS doesn't.
- **Edit:** `PUT /api/cron/jobs/{id} {updates: {name?, prompt?, schedule?, deliver?}}` → the job.
  - Send only what changed. The display form isn't always read back the same: a one-off shows as
    `once in 30m`, which would move it. Edit with `expr` (cron), `every {minutes}m` (interval) or
    `run_at` (one-off).
- **Pause, resume:** `POST …/{id}/pause`, `POST …/{id}/resume` → the job.
- **Run now:** `POST …/{id}/trigger`.
  - It answers only when the run ends, which can outlast the phone's 30 s wait. A timeout means
    the run is still going: it shows in the runs once stored.
  - 409 means it is already running.
- **Delete:** `DELETE …/{id}` → `{ok: true}`. The chats of past runs stay.
- **Runs:** `GET …/{id}/runs?limit=` → `{runs: [session-like rows]}`.
  - Times here are epoch seconds.
  - An agent run (`source: "cron"`, id `cron_<job>_<time>`) is a stored chat: open it like any
    other, under the row's `profile`. Its first user message carries Hermes' cron instructions
    ahead of the prompt.
  - A script's output (`source: "cron_output"`) is text only.
  - Run chats are left out of the chat list (`exclude_sources=cron`).
- **Delivery targets:** `GET /api/cron/delivery-targets` → `{targets: [{id, name, home_target_set,
  home_env_var}]}`.
  - `local` ("Local (save only)") comes first, then each connected platform and one
    `bot-chat:<profile>` per profile.
  - A platform without a home channel (`home_target_set: false`) delivers nowhere.
- **Blueprints:** `GET /api/cron/blueprints` → `{blueprints: [{key, title, description, category,
  tags, source, schedule, scheduleHuman, fields: [{name, type: time|enum|text|weekdays, label,
  default, options, optional, strict, help}], command, appUrl}]}`.
  - The `deliver` field's options are rewritten to `origin`, `local` and the connected platforms.
  - `POST /api/cron/blueprints/instantiate {blueprint, values: {name: value}}` → the job.
    - A blank left out takes its default.
    - `time` is `HH:MM`. `weekdays` is `everyday`, `weekdays` or `weekends`.
    - An unknown blank or a value outside a strict list is a 422 with `detail`; an unknown
      blueprint is a 404.
- **Per-job notify** (the push plugin, 0.33+, capability `cron-notifications`):
  - `GET /api/plugins/conduit_push/cron/notifications` → `{ok, muted: [job ids]}`.
  - `PUT` with `{job_id, notify}` → the same.
  - A job not listed notifies.
  - A run that answers `[SILENT]` never does.
  - The list lives in the profile's `conduit-cron.json`.
- **Pins** for jobs stay on the phone. Hermes' own `pinned` field locks a job's model; it isn't a
  pin.
- **Time zone:** cron times are read in the profile's `timezone` (`GET /api/config`). Empty means
  the host's own zone.

## The chat list: live state, read marks, scheduled runs

- **Live state:** `session.active_list` (gateway) → `{sessions: [{id, session_key, status, title, last_active, …}]}`.
  - `id` is the runtime id and `session_key` the stored one.
  - `status` is `working` (a turn runs), `waiting` (an approval or a question waits on the
    user), `starting` or `idle`.
  - It lists the chats live in the dashboard's gateway process. There is no event for changes, so
    the app asks every 4 s, backing off to 60 s while nothing changes, and again at once after
    `sessions.changed`. A gateway without the method answers `-32601`.
- **Read marks:** `PATCH /api/sessions/{id} {unread, profile}`.
  - Hermes keeps one watermark per chat (`last_read_at`). The chat is unread while its
    `last_active` is newer than the mark, and a chat never marked counts as read.
  - Nothing on Hermes moves the mark by itself: a client marks a chat read while it shows it.
  - `session.resume` touches `last_active`, so a chat opened while unread must be marked read
    after the resume, not before.
- **Scheduled runs:** the list call with `source=cron` in place of `exclude_sources=cron`.
- **Branches:** they are listed as their own rows, with `parent_session_id`. Compaction
  continuations are folded into their tip, and sub-agent runs are left out.

## Slash commands and side agents

- **Catalog:** `commands.catalog {session_id?}` → `{pairs, canon, commands, categories, skills, …}`.
  - Commands come from `categories[].pairs` (`["/name", "description"]`), then the top-level
    `pairs` (skills, quick and plugin commands).
  - Aliases come from `canon` (`"/alias": "/canonical"`). The usage hint is in the description,
    `"… (usage: /name <arg>)"`.
  - `commands["/name"].desktop` marks commands the desktop leaves out of its popover: `hidden`,
    `terminal`, `messaging`, `composer-voice`. `advanced` and `settings` are offered.
- **Running one:** `slash.exec {session_id, command}`, with `command` given without its slash.
  - The result is `{output, warning?}`, or a directive `{type, output?, message?, notice?,
    display?, target?}`:
    - `send` and `skill`: send `message` as an ordinary turn;
    - `prefill`: put `message` in the composer and show `notice`;
    - `alias`: run `target` instead;
    - `exec` and `plugin`: show `output`.
  - `slash.exec` doesn't stream chat events: the output is only in the result.
  - When `slash.exec` refuses (skills, for example: "use command.dispatch for /x"), run
    `command.dispatch {session_id, name, arg?}`, which returns the same directive.
- **The app runs these itself, as iOS does:**
  - `/new` and `/reset`: start a new chat (`session.create`).
  - `/branch` and `/fork`: `session.branch {session_id}` → `{session_id, stored_session_id,
    title, …}`. The branch is stored at once, with the transcript copied.
  - `/model` without an argument: open the picker.
  - `/yolo` without an argument: toggle it with `config.set`.
  - `/bg <text>` and `/btw <text>`: side agents.
  - `/compress [focus]`: compress the history.
- **Side agents:** `prompt.background` or `prompt.btw` `{session_id, text}` → `{task_id}` at
  once.
  - The answer comes later as the event `background.complete {task_id, text}` or
    `btw.complete {task_id, question, text}`.
  - The chat's history is not changed. A background task is stored as a session of its own
    (`bg_…`).
- **Compress:** `session.compress {session_id, focus_topic?}` can take minutes. The result is one
  of:
  - `{status: "compressed" | "aborted", summary: {noop, headline, token_line, note?}, messages,
    info, …}`;
  - `{lock_held: true, message}`;
  - `{status: "pending", message}`.

  `4009` means busy. Reload the history after a compression.

## Sub-agents, context, fast mode and YOLO

- **Sub-agent events during a turn:** `subagent.spawn_requested`, `.start`, `.progress`,
  `.thinking`, `.tool` and `.complete`.
  - Every payload has `{goal, task_count, task_index}`. Most add `subagent_id`, `delegation_id`,
    `status`, `model`, `tool_count`, `text` / `tool_preview` and `summary`.
  - A card is keyed by `subagent_id`, or by `delegation_id#task_index` until there is one.
- **Sub-agent methods:**
  - `subagent.list {session_id}` → `{subagents: [{subagent_id, goal, status, model,
    tool_count, last_tool, …}]}`. A card shown as working that is no longer listed has finished.
  - `subagent.tail {session_id, subagent_id}` → `{available, text}`.
  - `subagent.steer {…, text}` → `{status: "queued" | "rejected"}`.
  - `subagent.interrupt {…}` → `{found}`.
  - Deleting a chat deletes its sub-agent runs.
- **Context:** `session.context_breakdown {session_id}` → `{context_used, context_max,
  context_percent, context_estimated, categories: [{id, label, tokens}]}`.
- **Fast mode:** `config.set {session_id, key: "fast", value: "fast" | "normal"}`.
  - The model offers fast mode when `model.options` capabilities say `fast: true`.
  - `session.info.fast` reports it.
- **YOLO:** `config.set {session_id, key: "yolo", value: "1" | "0"}`, for this session only,
  and Hermes keeps it.
  - `session.info.yolo` reports it, and `session.info.approval_mode == "off"` locks it on.
  - The reply to opening a chat carries the same `info`.

## Reactions, replies and branches from a reply

- **Reacting:** `message.react {session_id, row_id, emoji, author: "user"}` → `{row_id, reactions:
  [{emoji, author, at, seen}]}`.
  - Use `newest_role: "assistant"` in place of `row_id` for a reply not stored yet.
  - Each author has at most one reaction per message: a different emoji replaces it, and the same
    emoji again takes it back. There is no add or remove flag.
  - The agent hears of the user's reactions with the next user message. Reacting doesn't start a
    turn.
  - Errors: `4040` (no such message in this chat) and `4024` (an empty emoji).
- **Where reactions are kept:** `display_metadata.reactions` on the stored row. A row's id is `id`
  on REST rows and `row_id` on the gateway's.
- **The agent's reaction:** the event `message.reaction {row_id, role, reactions}`.
  - It comes only from the agent's `react_to_message` tool, which is off unless the desktop turns
    it on.
  - It targets the user's latest message by default.
- **Replying to a reply:** there is no reply parameter. The message is sent as
  `[Replying to your earlier message]`, then the quoted reply as `> ` lines, a blank line, and
  the user's words. The desktop and iOS apps use the same marker and show it as a quote card.
- **Branching from a reply:** `session.branch {session_id, count, idempotency_key}`.
  - `count` is the number of user and assistant rows with text, hidden ones included, counted from
    the start up to and including the reply. Reasoning-only and tool rows don't count.
  - Hermes copies the rows (reasoning and markers too), the system prompt and the working
    directory, and sets the parent.
  - The iOS app sends `session.create` with the messages instead, and names the runtime id as the
    parent, which Hermes takes for a stored id.
- **Images:** `image.attach_bytes {session_id, content_base64, filename}` takes JPEG, PNG, GIF and
  WebP up to 25 MB. Hermes reads the format from the bytes.
- **Jumping to a message:** `GET /api/sessions/{id}/messages/around?row_id=` starts a page at a
  user row; it isn't centred on it.
  - Search hits carry no row id, so a hit can only open its chat.
  - The iOS app uses the endpoint only to fetch a prompt's images.

## Files, pictures and links in a transcript

- **Files on the host:** `GET /api/fs/read-data-url?path=&profile=` → `{dataUrl: "data:<mime>;base64,…"}`.
  - The limit is 16 MiB.
  - Errors: 403 (outside what Hermes manages), 404, 413 (too large).
  - `/api/fs/download` streams larger files.
- **Pictures the user sent:** stored prompts end in `@image:<path>` lines (quoted when the path has
  spaces). History read with `inline_images=false` shows each picture part as `[image]`.
  - Bots and messaging apps write `[Image attached at: <path>]` instead.
  - Hermes clears its media cache after about a day. The picture then comes from its prompt row:
    `GET /api/sessions/{id}/messages/around?row_id=&limit=1`, whose row keeps its picture parts
    inline (`image_url.url`, or a `source` / `data` part).
- **Files the agent names:** `MEDIA:<path>` in a reply. The path is absolute or `~/`, quoted when
  it has spaces, or wrapped as `![label](MEDIA:<path>)`. The kind comes from the extension.
  - Tags in code, inline code or a quote are text.
  - `[[audio_as_voice]]` and `[[as_document]]` are directives for messaging apps. The phone drops
    them.
  - A file the agent writes lands in Hermes' working directory, the folder its gateway runs in.
- **Links to chats:** `@session:<profile>/<id>` or `@session:<id>`, also as a link target
  `[label](@session:…)`. The `session_search` tool writes them.
- **The agent's plan:** `todo_list` (or the `tool_call` bridge calling it) answers
  `{todos: [{id, content, status, parent?}], revision, summary}`, where `status` is `pending`,
  `in_progress`, `completed` or `cancelled`.
- **Self-improvement review:** the event `review.summary {text}`, `💾 Self-improvement review: …`,
  with each action separated by ` · `. Hermes doesn't store it.
- **No longer sent:** `model.update` and `cwd.update`. The model and working directory come in
  `session.info`, and a model switch is a stored `model_switch` row.

## Formulas, diagrams and page previews in a reply

- **Formulas:** a display formula is `$$ … $$`, on one line or over several, or `\[ … \]` on
  their own lines. Inline `$…$` stays text, as on iOS.
- **Diagrams:** a fenced block whose language is `mermaid`.
- **Page previews:** `::preview{file="<path>"}` (or `file='…'`) at the start of a line or after a
  space. An `.html` or `.htm` file is drawn as a page; any other file becomes a file card.
  - The page is read through `/api/fs/read-data-url`, like any other file.
  - Hermes' desktop pages may call `window.hermes.send(text)` to post into the chat. The phone gives
    them a stand-in that answers `{ok: false, error: "undelivered"}`.
- Each of these inside code, or left unclosed, stays text.

## A profile's settings

Every route takes `?profile=`.
- **Read:** `GET /api/config` is the whole config with Hermes' defaults filled in.
  - `model` comes as the bare model name, so the provider comes from `GET /api/model/info`.
  - `model_context_length` is added (0 means the model's own size).
  - Secrets are not hidden: keys come back as stored. The phone shows none of them.
- **Schema:** `GET /api/config/schema` is `{fields: {"<dotted.key>": {type, description, category,
  options?}}, category_order}`. Its descriptions are made from the keys. It has no labels, defaults
  or ranges.
- **Save:** `PUT /api/config {config: <partial>}` deep-merges the partial config into the file.
  - Lists are replaced whole, and a key can't be removed this way.
  - A key Hermes holds at its default is left out of the file when it saves.
  - Hermes writes the YAML file again on each save.
- **Own routes:**
  - The memory provider: `PUT /api/memory/provider {provider}`, refused unless the provider is ready.
  - The context engine: `PUT /api/dashboard/plugin-providers {context_engine}`.
  - Their choices: `GET /api/dashboard/plugins/hub` → `providers.memory_options[]` (`name`,
    `status`; only `ready` ones can be picked) and `providers.context_options[]`.
- **Default model:** `POST /api/model/set {scope: "main", provider, model}` checks the provider can
  serve the model.
  - An expensive model answers `{confirm_required: true, confirm_message}`. Sending again with
    `confirm_expensive_model: true` sets it.
  - The choices are `GET /api/model/options?explicit_only=true`, the REST twin of `model.options`.
  - The default reasoning is `agent.reasoning_effort`. Blank means medium; `false`, `none` and
    `disabled` mean no thinking.
- **Fallbacks:** `fallback_providers`, a list of `{provider, model, ...}` (older entries are
  `"provider/model"` strings). There is no route of its own: the whole list is saved.
- **Delegate agents:**
  - Their model is `delegation.provider`, `delegation.model` and `delegation.reasoning_effort`.
    Blank follows the chat.
  - The live connection's `delegation.status` answers `{paused, active, max_spawn_depth,
    max_concurrent_children}`.
  - `delegation.pause {paused}` stops new ones being started, on every profile. Running ones carry
    on.
- **Applying:** Hermes reads its config again on each turn, so settings apply to the next turn or
  the next chat. A default model change applies to new chats.

## The gateway: status, connectors, logs and restart

Hermes' gateway is its own process: it serves the messaging platforms and scheduled jobs. The
phone's live connection goes to the dashboard instead, so it can stay up while the gateway restarts.
- **Status:** `GET /api/status`, readable without signing in. It gives `version`,
  `gateway_running`, `gateway_state`, `gateway_updated_at`, `gateway_exit_reason`,
  `gateway_shared_with` and `active_agents`.
  - States: `starting`, `running`, `degraded`, `draining`, `startup_failed`, `stopped`.
  - `gateway_updated_at` is the gateway's heartbeat; it changes about every minute and is stamped
    again after a restart.
  - A dashboard that needs sign-in leaves out the gateway's process id.
- **Connectors:** `GET /api/messaging/platforms` → `platforms[]` with `id`, `name`, `state`,
  `error_message`, `configured` and `enabled`.
  - States: `connected`, `connecting`, `retrying`, `fatal`, `disabled`, `paused`, `disconnected`,
    `not_configured`, `pending_restart`, `startup_failed`, `gateway_stopped`.
- **Logs:** `GET /api/logs?file=&lines=&level=&component=&search=` → `{file, lines[]}`.
  - `file` is `agent`, `errors` or `gateway`.
  - `lines` is at most 500.
  - `level` is a minimum level (`DEBUG`, `INFO`, `WARNING`, `ERROR`, in capitals).
  - `component` is `all`, `gateway`, `agent`, `tools`, `cli` or `cron`.
  - `search` matches text in the last 2000 lines.
- **Restart:** `POST /api/gateway/restart` runs `hermes gateway restart` and answers
  `{ok, pid, name: "gateway-restart"}` at once. A second call within 10 seconds joins the first.
  - Progress: `GET /api/actions/gateway-restart/status?lines=` → `{running, exit_code, lines}`.
  - Without a system service, the command becomes the gateway and never finishes. So the gateway
    counts as back once it runs in a settled state and one of these shows it really restarted:
    - the command ended well;
    - the gateway was seen down;
    - its heartbeat is a new one;
    - 30 seconds passed.
  - The phone stops waiting 90 seconds after the command ends, or after 10 minutes.
- **Busy mode:** what a message sent during a reply does is `display.busy_input_mode`
  (`interrupt`, `queue`, `steer`). The live connection reads it with `config.get {key: "busy"}`.

## Skills, toolsets and keys

Every route takes `?profile=`; the writes also take `profile` in their body.
- **Skills:**
  - `GET /api/skills` is a bare list of `{name, description, category, enabled, usage,
    provenance}`. `provenance` is `bundled`, `hub`, `agent` (learned) or `external`.
  - `PUT /api/skills/toggle {name, enabled}` writes `skills.disabled`.
  - `GET /api/skills/content?name=` gives `{content}`, the `SKILL.md`.
- **The official catalog:**
  - `GET /api/skills/hub/official` gives `skills[]` with `name, identifier, description, category,
    tags, installed`.
  - `GET /api/skills/hub/preview?identifier=` gives `skill_md`.
  - `POST /api/skills/hub/install {identifier}` runs `hermes skills install` in the background and
    answers `{name}`, the action to follow.
  - `POST /api/skills/hub/uninstall {name}` does the reverse.
- **Background actions:** `GET /api/actions/{name}/status?lines=` gives `{running, exit_code,
  lines}`. Hermes keeps them in memory, so one is gone (404) after the dashboard restarts.
- **Toolsets:**
  - `GET /api/tools/toolsets` is a list of `{name, label, description, enabled, configured, tools[]}`.
    `configured` is false when keys are missing.
  - `PUT /api/tools/toolsets/{name} {enabled}` changes it for the CLI platform's list, which the
    dashboard's chats use.
  - Use counts come from `GET /api/analytics/usage?days=365` → `tools[] {tool, count}`.
- **Backends:**
  - `GET /api/tools/toolsets/{name}/config` gives `{has_category, providers[], active_provider}`.
    Each backend has `name, badge, tag, env_vars[] {key, prompt, url, is_set}, is_active, status`
    (`ready`, `needs_setup`, `needs_auth`, `needs_keys`).
  - For `web`: `web_backend` and `capabilities` per backend, and `active_search_backend`,
    `active_extract_backend`, `search_via_nous`, `extract_via_nous`.
  - `PUT /api/tools/toolsets/{name}/provider {provider, capability?}` picks one. It answers
    `needs_nous_auth: true` when the backend needs a Nous Portal sign-in on the host.
- **The terminal:** `GET /api/tools/terminal/backends` → `{backends[] {name, label, description,
  active, status, detail}}`; `PUT /api/tools/terminal/backend {backend}`.
- **Keys:**
  - `PUT /api/env {key, value}` writes `.env`, rewrites copies of the old value in `config.yaml`, and
    adds the key to the credential pool (`auth.json`).
  - `GET /api/env` never gives a value back, only `is_set` and a redacted preview. The phone reads
    neither.
  - `DELETE /api/env {key}` removes one from all three places.
- All of these apply to new sessions.

## MCP connectors and agent plugins

- **Servers:** `GET /api/mcp/servers?profile=` → `servers[]` with `name, transport (http|stdio), url,
  command, args, auth (oauth|header), enabled, source, plugin`. A `source: plugin` server belongs
  to that plugin and is changed from Plugins.
  - `POST /api/mcp/servers/{name}/test` connects, lists the tools and disconnects. It answers
    `{ok, tools[] {name, description}}` or `{ok: false, error}`. An OAuth server without a token
    says "OAuth authentication required".
  - `PUT /api/mcp/servers/{name}/enabled {enabled}`, `DELETE /api/mcp/servers/{name}` (its keys stay
    in `.env`).
- **The catalog:** `GET /api/mcp/catalog` → `entries[]` with `name, description, transport,
  auth_type (none|api_key|oauth), required_env[] {name, prompt, required}, url, command, args,
  installed`.
  - `POST /api/mcp/catalog/install {name, env, enable}`. Most installs finish at once; one that
    needs a clone answers `{background: true, action}`.
- **OAuth sign-in:**
  - `POST /api/mcp/servers/{name}/auth` starts a flow and waits up to 30 seconds for the page. It
    answers `{flow_id, authorization_url, status, error}`.
  - The phone opens that page in the browser. The provider sends the browser back to the
    dashboard's `/api/mcp/oauth/callback/{name}`, built from the address the phone called, so it
    must be reachable from the phone.
  - `GET /api/mcp/oauth/flows/{flow_id}` gives `status` (`starting`, `authorization_required`,
    `approved`, `error`); Hermes waits five minutes for the callback.
  - `DELETE` on the same path drops the flow.
  - Starting a flow registers an OAuth client, saved as `mcp-tokens/{name}.client.json` on the host.
  - Changes to servers apply to new sessions, or with `/reload-mcp`.
- **Plugins:**
  - Installed ones are `GET /api/dashboard/plugins/hub` → `plugins[]` with `name, version,
    description, source, runtime_status, can_remove, can_update_git, path, auth_required,
    auth_command`.
  - The catalog is `GET /api/dashboard/plugins/catalog` → `entries[]` with `name, title, tier,
    capability_summary, installed, update_available`.
  - Changes act on the host's launch profile, whatever `profile` says:
    - `POST /api/dashboard/agent-plugins/{name}/enable` or `/disable`;
    - `POST …/{folder}/update {accept_capabilities?}`;
    - `DELETE …/{folder}`;
    - `POST …/install {catalog_name, enable}`.
  - An update that asks for more answers `{consent_required: true, delta_lines}` and changes
    nothing.
  - `restart_required` says when the gateway must restart; uninstalling always needs one.

## The task board (the kanban plugin)

Hermes bundles a `kanban` dashboard plugin; its routes are under `/api/plugins/kanban`.
- **Shared across profiles:** one board database serves every profile, so no call names a profile;
  each names its board with `?board=<slug>`.
- **Boards:** `GET /boards` → `{boards[] {slug, name, archived, is_current, total}, current}`.
- **The board:** `GET /board?board=&include_archived=` → `{columns[] {name, tasks[]}, assignees,
  latest_event_id, now}`. Columns come in workflow order: `triage, todo, scheduled, ready, running,
  blocked, review, done` (`archived` only when asked for).
  - Scheduled, running and review are Hermes' own: a task gets there only through the dispatcher
    or the worker. Setting `running` by hand is refused.
- **A task:** `GET /tasks/{id}` → `{task, comments[], events[], links {parents, children},
  link_tasks[] {id, title, status}, runs[]}`. Ids look like `t_1a2b3c4d`.
- **New tasks:** `POST /tasks {title, body, assignee, priority, triage, parents[]}`. Hermes puts
  one in triage when asked, in todo while a parent is open, or else in ready.
  - **To do means waiting on a parent:** a task without open parents is promoted to ready on the
    dispatcher's next tick.
- **Changes:**
  - `PATCH /tasks/{id} {status, title, body, priority, assignee}`; archiving is
    `{status: "archived"}`.
  - `DELETE /tasks/{id}` deletes a task for good.
  - `POST /tasks/{id}/comments {author, body}`.
  - `POST /tasks/{id}/reassign {profile|null, reclaim_first}`; null parks the task.
  - `POST /tasks/{id}/reclaim` stops its worker and requeues it.
  - `POST /tasks/{id}/specify` and `/decompose` (triage helpers) answer `{ok, reason}` with HTTP
    200 either way.
- **Running:** the dispatcher (in the gateway, every 60 seconds) starts ready tasks that have an
  assignee, highest priority first. It runs `hermes -p <assignee> chat -q "work kanban task <id>"`
  in the task's workspace.
  - `POST /dispatch` makes it look now.
  - The worker's chat is a session of its own with `source=kanban`, kept out of the chat list.
  - The worker's output is `GET /tasks/{id}/log?tail=`, under `kanban/logs/` on the host. A scratch
    workspace is under `kanban/workspaces/`.
- **Live:** `wss …/events?ticket=&board=&since=<latest_event_id>` sends
  `{events[] {id, task_id, kind}, cursor}`.
  - It takes a ticket in the query only (`POST /api/auth/ws-ticket`, single use), and Hermes sends
    nothing until something happens.
  - The board is read again after a burst of events.
- **New tasks' extras:** `POST /tasks` also takes `workspace_kind` (`scratch`, `worktree` or `dir`;
  omitted takes the board's default), `workspace_path`, `skills[]`, `goal_mode`, `goal_max_turns`,
  `model_override`, `provider_override` and `reasoning_effort` (`none` to `ultra`).
  - A task's model is changed with `PATCH /tasks/{id}`; `clear_model_override` and
    `clear_reasoning_effort` go back to the profile's.
  - `GET /model-options` → `{providers[] {slug, label, models[]}}`: the models a task can be given.
- **Several tasks:** `POST /tasks/bulk {ids, status, assignee, reclaim_first, priority, archive}`
  answers `{results[] {id, ok, error}}` with HTTP 200 even when some fail. There is no bulk delete.
  - Hermes refuses some moves, such as Triage to Blocked (`transition to 'blocked' refused`; the
    same move by `PATCH` is a 409).
- **Boards:** `POST /boards {slug, name, description, switch}` (the slug is fixed for good),
  `PATCH /boards/{slug} {name, description}`, and `DELETE /boards/{slug}` to archive one
  (`?delete=true` deletes it). The default board can't be removed.
  - On a Windows host, archiving fails with HTTP 500 (`WinError 5`, access denied): Hermes renames
    the board's folder while the dashboard still has its database open.
- **Routing (the same for every board):** `GET /orchestration` → `{orchestrator_profile,
  default_assignee, auto_decompose, auto_promote_children, resolved_orchestrator_profile,
  resolved_default_assignee}`; `PUT /orchestration` changes only the fields sent, in `config.yaml`
  under `kanban`.
  - With `auto_decompose` on, the dispatcher writes up a new triage task, promotes it and runs it
    with the default assignee.
- **Profiles:** `GET /profiles` → `{profiles[] {name, is_default, description, description_auto}}`.
  `PATCH /profiles/{name} {description}` saves what a profile is for (in its `profile.yaml`);
  `POST /profiles/{name}/describe-auto {overwrite}` has Hermes write it with its model and answers
  `{ok, reason, description}` with HTTP 200 either way.

## Files on the host (`/api/fs/...`) and artifacts

- **A chat's working folder** is `cwd` in `session.info` (with `branch` and `project`), and in the
  resume snapshot's `info`. Hermes sends `session.info` again when the folder changes.
- **Listing:** `GET /api/fs/list?path=&profile=` → `{entries[] {name, path, isDirectory}}`, folders
  first. Paths come back native (`C:\...` on Windows).
  - A folder Hermes can't read answers **HTTP 200** with `{entries: [], error}` (`ENOENT`,
    `ENOTDIR`, `EACCES`, …).
  - Dotfiles are listed, but `.git`, `node_modules`, `build`, `dist`, `target`, `venv` and the like
    never are, nor Hermes' secrets (`.env`, `auth.json`, `config.yaml`, …).
  - On an unmanaged host (no `HERMES_DASHBOARD_FILES_ROOT`) the whole disk is readable.
- **A file's start:** `GET /api/fs/read-text?path=` → `{binary, byteSize, language, mimeType, text,
  truncated}`, at most 512 KiB of text; over 64 MiB is a 413.
- **A whole file:** `GET /api/fs/download?path=` streams it (no size cap; header or cookie auth),
  and `GET /api/fs/read-data-url?path=` sends one up to 16 MiB as `{dataUrl}`. FastAPI's own
  `{"detail": "Not Found"}` means a Hermes without the route; a missing file says "File not found".
- **Artifacts** come from the push plugin, not Hermes (capability `artifacts` in
  `/api/plugins/conduit_push/capabilities`):
  `GET /api/plugins/conduit_push/artifacts?limit=&session_id=…&kind=&q=&missing=hide&before=&profile=`
  → `{ok, items[], next_before}`.
  - `session_id` repeats, one per id a chat is known by, and the plugin follows the chat's parents
    and branches. `kind` is `images`, `pages`, `files` or `links`.
  - Each item has `kind` (`image|video|audio|page|document|file|link`), `source`
    (`delivered|preview|written|attached|link`), `name`, `path` or `url`, `host`, `exists`, `size`,
    `session_id`, `session_title`, `message_id` (the stored row that mentions it) and `timestamp`.
  - The plugin reads the profile's session store; it allows 60 requests a minute and two at once.

## Projects (`projects.*`) and moving a chat

- **Projects** are JSON-RPC methods on the live connection. Their parameters are strict: an unknown
  key is refused (4000). Each takes an optional `profile`. Errors: 5061 (failed), 5062 (no such
  project), 5063 (invalid, e.g. a folder that already belongs to another project).
- **Membership comes from folders:** a chat is in the project whose folder holds its `cwd` (or its
  git root), the deepest folder winning. No chat names its project.
  - Chats in no project, and in no git repository, go to **Home** (`__no_project__`).
  - Chats in a git repository no project claims form an **automatic** project (`isAuto`), as do
    folders without git that chats work in. These can't be renamed or deleted.
  - Scheduled runs, task-board workers, one-shot sessions and empty chats are left out.
- **The tree:** `projects.tree {preview_limit}` →
  `{projects[] {id, label, path, color, icon, isAuto, isNoProject, sessionCount, lastActive, repos,
  previewSessions, sessionIds}, active_id, scoped_session_ids}`. `sessionIds` names every chat the
  project counts.
- **A project's chats:** `projects.project_sessions {project_id}` → `{project}` with
  `repos[] {label, groups[] {id, label, path, sessions[]}}`: one group per folder (a checkout, a
  worktree).
- **Changes:** `projects.create {name, folders[], primary_path, use}` (`use` also makes it Hermes'
  active project), `projects.update {id, name}`, `projects.delete {id}`.
  - Folders aren't checked to exist, and a trailing separator is cut: a drive root is stored as `D:`.
  - Deleting a project keeps its folders, files and chats. It leaves `active_id` pointing at it.
  - An idea is written as `IDEA.md` in the first folder with `POST /api/fs/write-text {path,
    content}`. That route makes no folders and has no secrets check.
- **Moving a chat:** `session.workspace.move {session_key: <stored id>, cwd}` → `{cwd, branch,
  git_repo_root}`.
  - Hermes points the chat (and a running agent) at the folder, restarts its terminal there, and
    sends `session.info`. No files move.
  - The folder must exist (4017).
- **A new chat in a folder:** `session.create {cwd, cwd_explicit: true}`; without `cwd_explicit`, a
  named profile's own `terminal.cwd` wins.
- **A folder picker's start:** `GET /api/fs/default-cwd` → `{cwd, branch}`. No route lists the
  drives.

## Opening a chat by its id

- `GET /api/sessions/{id}?profile=` answers the stored row, or 404 "Session not found". Use it
  before opening a chat from a link: resuming an id Hermes doesn't have stores an empty chat
  under that id.

## Bots (`profiles.*`)

`profiles.*` calls take no `profile`: they name the bot. Bot Mode keeps each bot's look in its
profile's `ui_meta['hermes-bots']`.

- **The roster:** `profiles.list` → `{profiles[], bot_mode_protocol, install_id}`.
  - A row is `{name, display_name, description, has_avatar, ui_meta, ui_meta_revisions,
    canonical_session {id, resolved_id, last_active, preview}, last_session, previous_names,
    is_default}`.
  - The look is `{title, pinned, hidden, color, custom, imageKind, created, …}`. `color` is a hex
    or an `hsl(…)` string. Other clients' keys are kept on every write; `image`, `pet` and `chat`
    never travel.
  - `bot_mode_protocol: true` means the host adds the bot-messaging section to every SOUL itself.
- **Making one:** `profiles.create {name, description, soul}`. The name must match
  `[a-z0-9][a-z0-9_-]*`, 64 at most.
- **Reading and writing:** `profiles.describe {name}` → `{description, soul}`.
  `profiles.configure {name, ui_meta: {hermes-bots: <whole look>}, ui_meta_expected_revisions:
  {hermes-bots: n}, description?, soul?}` → `applied {ui_meta, ui_meta_conflicts, description,
  soul}`. A look named in `ui_meta_conflicts` lost to a newer revision: read the roster again,
  merge, and write once more.
- **Its picture:** `profiles.set_asset {name, asset: "avatar", data: "data:image/png;base64,…"}`,
  or `clear: true`; `profiles.get_asset {name, asset}` reads it.
- **Deleting one:** the dashboard's `DELETE /api/profiles/{name}` removes the profile folder with
  its chats, memory and files. There is no socket method.
- **Its chat** is the session titled "Bot Chat" in that profile:
  - Found with `session.list {profile, title: "Bot Chat", limit: 200, include_hidden: true}`,
    preferring the roster's `canonical_session`.
  - Made with `session.create {cols, source: "desktop", profile, title, hidden: true,
    follow_profile_config: true}` and then `session.title`. A title already taken (4022, or
    "already in use") means another client made it first: look again.
  - The dashboard's `/api/sessions` lists don't show hidden chats.
- **@mentions** are the client's: the text goes as typed, with a closing note,
  `[@mentions resolved from the Bot Mode roster — the user is referring to: @tag = agent profile
  "name" ("Title"). …]`. Hermes stores and shows it as part of the message.

## Group chats (`groups.*`)

Hosted rooms, protocol version 2. Every `groups.*` call goes without a profile: rooms are the whole
gateway's. A room isn't a session; Hermes' driver runs its members' turns without the phone.

- **What it can do:** `groups.capabilities` → `{protocol_version, driver, methods[],
  max_log_limit, features}`. A room needs `groups.list`, `create`, `state`, `send`, `log` and
  `disband`; `stop` and `approve` are optional.
- **Rooms:** `groups.list {include_disbanded}` → `{rooms[], next_offset}`. A room is `{room_id, name,
  members[] {member_id, profile, handle, display_name, target, previous_names}, revision,
  updated_at, disbanded_at, latest_seq}`.
- **Making one:** `groups.create {room_id, name, members[]}`. The client makes `room_id`
  (`[A-Za-z0-9][A-Za-z0-9._:-]*`, up to 128) and it is the retry key. Members (2–6) are
  `{member_id, profile, handle, display_name}`; `all`, `everyone` and `user` are reserved handles.
- **Reading one:** `groups.state {room_id, include_disbanded: true}` → `{room, driver_status
  {running, working, blocked, counts, pending_actions[]}}`. Without `include_disbanded` a
  disbanded room answers 4112/4114 ("hosted room not found").
- **Its log:** `groups.log {room_id, since_seq, limit, include_disbanded}` → `{events[], cursor,
  latest_seq, has_more, authority}`. An event is `{room_id, seq, event_id, kind, actor {kind, id,
  profile, display_name}, payload, created_at}`.
  - Kinds: `message.user`, `message.member` (`payload.text`, `thread_id`, `member_id`,
    `round_index`, `discussion_event_id`), `turn.*` per member turn (`settled` with `passed` or
    `message_event_id`, `failed`, `cancelled`, `deferred`, …), and `room.activity` (`status`
    settled or bounded), `room.stop_requested`, `room.renamed`, `room.members_changed`,
    `room.disbanded`.
  - There is no stream: clients poll. iOS and this app poll every 3 s.
- **Sending:** `groups.send {room_id, event_id, payload {text, thread_id: "main"}}` →
  `{event, accepted, driver_started}`. The gateway files it as `user:` + SHA-256 of the trimmed
  `event_id`, so the same id again is the same message.
- **Approvals:** a member's tool call waiting on the user is a `pending_actions` row
  `{kind: "approval", member_id, task_id, execution_generation, request_id, approval {command,
  description, choices}}`. Answer it with `groups.approve {room_id, member_id, task_id,
  execution_generation, choice: once|deny, request_id}`; `result.resolved` 0 means it was no longer
  held.
- **Stop and disband:** `groups.stop {room_id}` → `{cancelled}`; `groups.disband {room_id}` ends it
  for good.
- **Hermes Desktop's group chats** aren't hosted rooms: Desktop runs them and syncs a copy to the
  default profile's `ui_meta['hermes-bots-groups']` (`{version, rooms {key: {roomId, name,
  members[] {name, handle}, log[] {id, from {kind, name}, text, at (ms), truncated}, omitted,
  revision, image}}, deleted}`), readable from `profiles.list`.
- **Files and pictures** come from the push plugin, since a room has no workspace:
  - `POST room-files {name, data_base64}` (≤ 50 MB) → `{ok, ref_text: "@file:…", path}`. The
    message carries each `@file:` line, a blank line, then the text.
  - `GET room-images` → `{ok, revisions {room: rev}}`; `GET room-images/{room}` →
    `{ok, rev, data_base64}`; `PUT room-images/{room} {data_base64}` → `{ok, rev}`; `DELETE` clears
    it. On this Windows host the plugin's read, list and clear fail (`os.O_NONBLOCK`).

## Calls from Hermes (the push plugin, `/api/plugins/conduit_push/calls…`)

Every route takes `?profile=`. 30 requests a minute; bodies up to 4 KiB.

- **Settings:** `GET calls` → `{ok, paired, settings, bounds, rules_max, watches}`.
  - `settings` is `{enabled, when_asked, decides, alerts, min_gap_s, per_hour, per_day, rules,
    avoid_places, avoid_unknown_place}`.
  - `bounds` gives `{min, max}` for `min_gap_s` (30–3600), `per_hour` (1–30) and `per_day`
    (1–60).
  - The host keeps them in the profile's `conduit-calls.json`. Calls are off by default.
- **Saving:** `PUT calls {settings: {…}}` → `{ok, settings}`. An unknown key is 400, so a client
  sends only the keys the host reported.
- **A call:** when a watched turn ends, the plugin sends a `call.requested` event to the relay
  instead of the usual push, unless the call is off, busy or past a limit. The sealed inner
  payload carries `call: {id (24 hex), kind (done|failed|stopped|approval|question), title,
  session_ids (≤4), reason?}`.
  - Watches come from Hermes' `conduit_call_user` tool ("call me when it's done"), from scheduled
    checks (`conduit_call_watch`), from alerts (an approval or question unanswered for 60 s), or
    from `POST calls/watches {session_ids, title, hold_s, ended_within_s?}`.
  - The relay sends Android an ordinary high-priority FCM data message: category `HERMES_CALL`,
    no `sent_at`, no TTL. The envelope's `iat`, bound to the ciphertext, says when it was placed;
    the phone treats a call more than 90 s old as missed.
  - The tool is shown to the model only while the profile is paired and `enabled` with
    `when_asked` or `decides` on.
- **Unanswered:** `POST calls/outcomes {call_id, session_ids, outcome: declined|missed, age_s
  (≤86399), kind?, title?, reason?}` → `{ok, status: "recorded"}`. The chat's next turn hears it
  once. 409 means the profile isn't paired.
- **Presence:** `PUT calls/presence {hold_s}` keeps calls from ringing while the user is in a call
  (0 ends it). This app doesn't send it: it has no live calls.

## Our relay: a running turn's progress (`live-activities`)

- The phone registers a turn it sent: `PUT {relay}/v1/installations/{id}/live-activities`
  (installation credential) with `{push_token: <a UUID the phone made>, session_ids: [≤ 6 ids]}`.
  An iPhone sends its ActivityKit token instead. `DELETE` with `{push_token}` removes it.
- The push plugin reports each turn to the relay (`POST /v1/live-activity {session_id, state
  {phase, tool, tools, context}, end}`), at most every 5 s, and stops when the relay answers
  `tracked: false`.
- For an Android installation the relay sends an FCM data message:
  - `category: HERMES_LIVE_TURN`, `activity`, `event` (update or end) and `phase` (thinking,
    tool, approval, input; done, failed or stopped at the end);
  - `tools`, and, when known, `tool` (one of 17 categories), `context` (%), `stale_at` or
    `ended_at` (seconds).
  - Waiting on the user and the end are high priority; the rest normal.
  - Nothing in it is sealed: it says nothing about the turn's content.

## The push plugin's other routes (`/api/plugins/conduit_push/...`)

- **Which app sent a turn:** the plugin reads the gateway socket's `X-Conduit-Client` header. With
  `conduit`, a turn counts as the phone's: its reply-source mute applies, and `conduit_show` cards
  are allowed. A `Mozilla/` user agent without it counts as Desktop or the web chat. This app
  sends `conduit`.
- **What it serves:** `GET /capabilities` → `{ok, version, loaded_version, restart_needed,
  boot_id, capabilities[]}`. A 404 or 405 means a plugin before 0.4, or none.
  - Hermes' plugin list (`/api/dashboard/plugins/hub`) then tells missing from not served.
- **Taking a chat over:** `prompt.submit` refuses a chat Hermes Desktop or a terminal holds with
  4090 (`data.reason: SESSION_NOT_OWNED`). The message reads "Details: session <id> opened by
  <surface>".
  - `POST /sessions/takeover {session_ids[≤4]}` → `{ok, status: free|taken_over|busy|same_host}`.
    `busy` means the other app is replying (or a CLI or task worker holds the chat), so ask again.
  - Only Desktop and terminal-UI claims are dropped. A 501 means a Hermes that can't hand chats
    over. 60 requests a minute for the whole dashboard.
- **Desktop read state:** `GET /sessions/desktop-views?since=` → `{ok, observing, views: {<stored
  id>: {seen_through, open?}}}` (host seconds). It is read-only: Hermes' own read flag is never
  written.
  - Opens are counted only from a `Mozilla/` socket without `X-Conduit-Client`.
  - A chat still on screen reports "now" and shouldn't move the cursor on.
- **Reply sources:** `GET/PUT /notifications/reply-sources {source: conduit|desktop|messaging|
  terminal, notify}` → `{ok, sources}`. Only the "reply ready" notification follows them.
- **Turn surface** (`POST /turn-surface`) takes only Apple places, so this app doesn't send it.
- **The exchange:** `POST /present/exchange?profile= {installation_id, device, app_version,
  features[], results: [{id, outcome: confirmed|declined|edit, via: tap|voice|auto}]}` →
  `{ok, prompts: [{id, title, preview, action_label, spoken_yes, session_id, created_at,
  expires_at, maps?}], cards?}`.
  - 409 while the profile isn't paired with the plugin; 60 a minute per profile.
  - `installation_id` (8–64 of `[A-Za-z0-9_-]`) names the phone; it doesn't authenticate it.
  - `features` decides what Hermes is offered. `chat-cards` offers `conduit_show` in chats;
    `info-cards`, `media-cards` and `live-cards` its other kinds. Those stay in a chat's history,
    so they're offered only while every phone that draws cards says it draws them.
    `show-cards` (a voice conversation's screen, which brings `cards`) is sent since T11b.
    `maps-navigate` (prompts that open Maps) is for later.
  - The first answer wins; answers for unknown or finished prompts are ignored.
- **Settings Hermes may change** (plugin 0.19+): `PUT /conduit-settings?profile=
  {installation_id, device, app_version, mode: off|ask|small, settings: [{key, title, section,
  description, tier: small|ask, kind: bool|choice, value, choices?: [{value, title}]}], results:
  [{id, status, note?}]}` → `{ok, pending: [{id, key, title, value, from?, tier, reason, …}]}`.
  - The phone shares only what it lets Hermes see; Hermes' `conduit_settings` tool reads them and
    asks for changes, which wait in `pending` until the phone reports an outcome.
  - Outcomes: `applied`, `dismissed`, `conflict` (the user changed it after Hermes read it),
    `refused` (not shared, a wrong value, or the mode off) and `undone`.
  - The phone checks each request against its own list again. In `small` mode a small setting
    applies at once with an Undo; anything else waits for the user's Apply.
  - 409 while the profile isn't paired with the plugin; 404 or 405 on a plugin without it.
  - The plugin keeps the devices, requests and outcomes in `conduit-settings.json` in Hermes' home.
- **Confirmations** (`conduit_present confirm`) wake the phone with `phone.changes` in alert mode.
  The relay sends background ones another way and drops `mode` from the routing, so any
  `phone.changes` that arrives as a normal push is an alert.
- **`conduit_show`'s answer** is `{ok: true, cards: [...]}` (normalized), so the chat's history
  keeps the cards.
  - Hermes may call it through `tool_call` (`{"calls": [{name, arguments}]}`, sometimes a JSON
    string). The stored call is then `tool_call`, but its answer's row has
    `tool_name: conduit_show`.
- **Testing a notification** is the CLI's `hermes conduit-push test`; there is no route.
- **Updating the plugin:**
  1. `POST /api/dashboard/agent-plugins/conduit_push/update`. If Hermes doesn't know the plugin,
     `POST /api/dashboard/agent-plugins/install {identifier: "kaishi00/hermes-conduit-notifier",
     enable, force?}` instead.
  2. A gateway restart.
  3. `POST /self/restart`, which restarts the dashboard: 409 while chats reply, 501 on Windows.
     Then wait for a new `boot_id` on `/capabilities`.
