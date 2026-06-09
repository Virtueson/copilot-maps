# Copilot Maps — Session Handoff (resume point)

**Last updated:** during Step 4a execution, paused by user before Task 8.
**Working tree:** clean. **HEAD:** `ed9d4c0` (Step 4a Task 7 done).

## Where we are RIGHT NOW

Executing the **Step 4a (text copilot)** plan:
`docs/superpowers/plans/2026-06-01-copilot-maps-step4a-text-copilot.md`

- ✅ **Phase 1 (backend) Tasks 1–6 DONE** — copilot models, shared `search_with_fallback`,
  `CopilotProvider`+`StubCopilot`, `search_places` tool, Anthropic agent loop
  (`run_agent_loop` + `AnthropicCopilot`, fake-client tested), `POST /copilot/ask` +
  config factory. **All 19 backend pytest pass.**
- ✅ **Phase 2 Task 7 DONE** — app copilot DTOs (`CopilotDtos.kt`), `CopilotApi.kt`,
  `NetworkModule.copilotApi`. Compiles.

### ▶ NEXT TASK: Task 8 — domain + repository (NOT yet started)
Create:
- `android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt`
  (`Role{USER,ASSISTANT}`, `ChatMessage(role,content)`, `RouteSummary`, `TripContext`,
  `CopilotResult` sealed)
- `android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt`
  (`interface` + `DefaultCopilotRepository(api)`, maps domain→DTO, role→lowercase)
Then compile (`:app:compileDebugKotlin`) and commit. **Full code is in the plan, Task 8.**

### Remaining after Task 8
- **Task 9** — `CopilotState.kt` + `CopilotViewModel.kt` (TDD, 2 unit tests) + `CopilotViewModelFactory`.
- **Task 10** — `CopilotChatSheet.kt` + wire into `MapScreen.kt` (chat FAB + `ModalBottomSheet`,
  `buildTripContext`/`trafficLabel` helpers). On-device verify with **stub copilot**.
- **Task 11** — USER creates Anthropic API key (console.anthropic.com), adds
  `COPILOT_PROVIDER=anthropic` + `ANTHROPIC_API_KEY=...` to `backend/.env`; restart backend;
  on-device verify real Claude. Then final commit + push.

## How we work (do NOT re-derive)
- **On `master` directly** (user consented). **Guided-inline**: Claude writes + tests all
  backend/app code via CLI; USER does the Anthropic key (Task 11) + on-device checks
  (Task 10 step 8, Task 11). Each green TDD step → its own commit (see plan).
- After finishing Step 4a: run finishing-a-development-branch spirit (verify all tests, update
  memory, **push**).

## Commands / environment
- Backend: `cd backend; .\.venv\Scripts\python.exe -m pytest -q` ; run server
  `.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000`.
- Gradle: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android;
  .\gradlew.bat :app:compileDebugKotlin` (or `:app:testDebugUnitTest`).
- adb: `C:\Users\Russell\AppData\Local\Android\Sdk\platform-tools\adb.exe reverse tcp:8000 tcp:8000`.
- Model id (copilot): **`claude-haiku-4-5`** (no date suffix — per claude-api skill).
- `backend/.env` currently has Google route+places keys; copilot keys (`COPILOT_PROVIDER`,
  `ANTHROPIC_API_KEY`) are added by USER in Task 11. `.env` is git-ignored.

## Git / backup
- Remote: `https://github.com/Virtueson/copilot-maps` (private, gh authed as Virtueson).
- **7 commits unpushed** (`e6d405b..ed9d4c0`). `git push` when convenient (after Step 4a, or now).

## Project status (5-step roadmap)
1. Map+GPS ✅ · 2. Routing ✅ · 2b. Traffic colors ✅ · 3. Places ✅ ·
**4a. Text copilot — IN PROGRESS (Task 8 next)** · 4b. Voice — later · 5. Live nav — later.

## Parked / future ideas (raise when relevant, not blocking)
- **Camera auto-fit** after a places search (Step 3 polish the user wants to revisit).
- **Profile memory** (preferences injected as context) and local chat persistence.
- **Landmark-at-turn** ("what building before the left turn"): needs turn-by-turn steps
  (Step 5) + Google **Address Descriptors** tool (experimental in Indonesia).
- **Step 4b voice**: Android SpeechRecognizer (mic→text→sendMessage) + TextToSpeech.

## Resume instruction
Re-read this file + the Step 4a plan, then continue at **Task 8**. Do NOT re-brainstorm or
re-plan Step 4a — design + plan are approved and committed.
