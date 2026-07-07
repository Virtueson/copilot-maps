# Copilot Run Logging to Supabase — Design

**Date:** 2026-07-08
**Status:** Approved for planning

## Problem

The Copilot is a black box in production. When it answers, we can't later inspect
*what memory it was given*, *which tools it used*, *how many agent-loop iterations it
took*, or *what it answered*. There is no record of any run. We want observability —
especially the ability to see the exact conversation memory that was fed to the LLM for
a given answer.

## Goal

Persist one audit row per Copilot question to a Supabase (Postgres) table, capturing the
run's session, the memory snapshot sent to the LLM, the tools used, the loop count, and
the answer — **without changing where memory lives** (it stays client-side on the phone,
the last 12 turns) and **without ever breaking the Copilot if logging fails**.

## Key decisions (agreed during brainstorming)

- **Observability only.** Memory continues to live in the app (`CopilotViewModel`, last 12
  turns, re-sent each call). Supabase stores a *copy* of what was sent, not a live memory.
- **`memory_messages` is the headline field:** the exact `messages` array the LLM received,
  stored verbatim as JSON, so a row shows "this answer came from *this* memory."
- **Backend is the single logging point.** `/copilot/ask` already receives the memory +
  context and produces the answer, so it writes the row.
- **Session = one conversation.** The app generates a `session_id` (UUID) when a fresh
  conversation starts (chat opened fresh / after Clear) and reuses it for that
  conversation's turns. Not per-app-launch, not a permanent device id.
- **Best-effort, fire-and-forget insert via `httpx`** to Supabase's REST (PostgREST)
  endpoint — no `supabase-py` dependency. A logging failure is swallowed (warning logged);
  the driver always gets their answer.
- **Config-gated.** If `SUPABASE_URL`/`SUPABASE_SERVICE_KEY` are unset, logging is a silent
  no-op — local dev and the test suite need no Supabase.
- **Storing the rendered system prompt is out of scope** — `memory_messages` + `trip_context`
  are enough; the persona is static and lives in the repo.

## Architecture

### The table: `copilot_runs`

```sql
create table copilot_runs (
  id              uuid primary key default gen_random_uuid(),
  created_at      timestamptz not null default now(),
  session_id      text,
  user_message    text,                 -- the latest user turn
  memory_messages jsonb,                -- ⭐ exact messages array sent to the LLM
  trip_context    jsonb,                -- origin + routes snapshot sent as context
  tools_used      text[]  default '{}', -- e.g. {search_places}
  loop_count      int,                  -- agent-loop iterations taken
  answer          text,                 -- final reply ("" if error)
  answer_language text,                 -- "id" | "en" (existing detector)
  model           text,                 -- e.g. deepseek-v4-flash
  provider        text,                 -- openai | anthropic | stub
  latency_ms      int,
  error           text                  -- null on success
);
```

(DDL is created by the developer once in the Supabase SQL editor — not run by the app.)

### Data flow

```
Phone (CopilotViewModel)
  ├─ new conversation → generate session_id (UUID), keep for the conversation
  └─ POST /copilot/ask { messages, context, session_id }
        │
Backend /copilot/ask
  ├─ t0 = now()
  ├─ reply, tools_used, loop_count = copilot.ask(messages, context)   # loop returns metadata
  ├─ answer_language = detect_language(reply)
  ├─ latency_ms = now() - t0
  └─ best-effort:  log_run(row)  →  httpx POST {SUPABASE_URL}/rest/v1/copilot_runs
        (failure / unconfigured → warning, swallowed)          headers: apikey + Bearer service key,
                                                               Prefer: return=minimal
```

### Backend changes

1. **Agent loop returns metadata.** Today `ask()` returns a `str`. Change the loop
   functions (`run_openai_agent_loop`, `run_agent_loop`) and the provider `ask()` methods to
   return a small result carrying the reply plus `tools_used: list[str]` and
   `loop_count: int`. The stub copilot returns the same shape (empty tools, 1 loop).
   - `tools_used` = the distinct tool names dispatched across all iterations.
   - `loop_count` = number of LLM round-trips actually taken.

2. **New `app/logging/supabase_logger.py`** (or `app/copilot/run_log.py`):
   - `build_run_row(session_id, messages, context, result, answer_language, model, provider, latency_ms, error) -> dict` — pure function assembling the row (unit-testable).
   - `async def log_run(row: dict) -> None` — best-effort `httpx` POST; returns silently if
     `SUPABASE_URL`/`SUPABASE_SERVICE_KEY` are unset or the request errors (logs a warning).

3. **Config** (`app/config.py` `Settings`): add `supabase_url: str = ""`,
   `supabase_service_key: str = ""`. A helper `supabase_logging_enabled()` = both set.

4. **Request model / DTO:** add `session_id: str | None = None` to `CopilotAskRequest`
   (backend) and `CopilotAskRequestDto` (app).

5. **Router `/copilot/ask`:** time the call, unpack the new result, run `detect_language`
   on the reply (already does), then `await log_run(build_run_row(...))` inside a
   `try/except` that never propagates. Response to the app is unchanged
   (`{reply, language}`) — `session_id` and metadata are for logging only.
   - On a `CopilotError`, still log a row with `error` set and `answer=""` before
     re-raising the 502, so failures are observable too.

### App changes

- **`session_id` generation:** `CopilotViewModel` holds a `sessionId` (a
  `UUID.randomUUID().toString()`), created on init and **regenerated whenever the
  conversation resets** (the existing "Clear"/new-chat path). It is passed into
  `CopilotRepository.ask(...)` → `CopilotAskRequestDto.session_id`.
- No UI change. No change to how memory is held or trimmed.

## Error handling / edge cases

- **Supabase down or slow:** the POST has a short timeout (e.g. 5 s); any exception is
  caught and logged as a warning — the answer has already been returned to the caller, so
  the driver is unaffected. (Logging happens after the reply is computed; consider firing it
  without awaiting completion so it never adds latency — see Open question.)
- **Unconfigured (no env):** `log_run` returns immediately; zero behavioral impact. This is
  the default for local dev and CI.
- **Copilot error path:** a row is still written with `error` populated and `answer=""`.
- **Large `memory_messages`:** capped implicitly by the app's 12-turn window; no extra
  truncation needed.
- **Secrets:** `SUPABASE_SERVICE_KEY` is a service-role key → **only in git-ignored
  `backend/.env`**, never client-side, never committed.

## Testing

**Backend (pytest, stub-only, no live Supabase):**
- `build_run_row(...)` maps a sample `messages`/`context`/result into the expected dict
  (session_id, memory_messages, tools_used, loop_count, answer, latency, model, provider).
- Agent-loop metadata: a loop that calls a tool once then answers reports
  `tools_used == ["search_places"]` and the correct `loop_count`; a no-tool answer reports
  `tools_used == []`.
- `log_run` is a **no-op when unconfigured** (no `SUPABASE_URL`) — asserted without any
  network call.
- `log_run` **swallows POST failures** — inject a fake httpx client that raises; `log_run`
  must not propagate.
- `/copilot/ask` still returns `{reply, language}` and does not fail when logging is
  unconfigured (override `get_copilot` with a fake; Supabase env unset).

**Manual (developer, once):**
- Create the `copilot_runs` table in Supabase; set env; ask the Copilot a question that
  triggers `search_places`; confirm a row appears with the memory, `tools_used`,
  `loop_count`, and answer populated.

## Open questions (resolve in planning)

- **Await vs. fire-and-forget:** to guarantee zero added latency, dispatch `log_run` as a
  background task (`asyncio.create_task`) rather than awaiting it in the request path.
  Leaning fire-and-forget, with the row built synchronously (cheap) and only the POST
  detached. Confirm during planning.

## Files touched (indicative)

**Backend**
- `app/config.py` — `supabase_url`, `supabase_service_key`, `supabase_logging_enabled()`.
- `app/copilot/run_log.py` — new: `build_run_row` + `log_run`.
- `app/copilot/openai_compat.py`, `app/copilot/anthropic_agent.py`, `app/copilot/stub.py` —
  loop/`ask` return reply + `tools_used` + `loop_count` (a small result type in
  `app/copilot/base.py`).
- `app/models.py` — `CopilotAskRequest.session_id`.
- `app/routers/copilot.py` — time, unpack, log best-effort.
- `backend/tests/` — `test_run_log.py` (+ loop-metadata assertions).

**App**
- `network/CopilotDtos.kt` — `CopilotAskRequestDto.session_id`.
- `data/CopilotRepository.kt` — pass `session_id` through.
- `ui/copilot/CopilotViewModel.kt` — hold + regenerate `sessionId`, send it.

## Non-goals

- Server-side memory / persistence (memory stays on the phone).
- A dashboard/UI over the logs (query in Supabase directly for now).
- Auth/RLS design beyond using the service-role key from the backend.
- Logging non-Copilot endpoints (`/routes/plan`, `/places/search`).
