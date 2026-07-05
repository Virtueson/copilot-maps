# Copilot Maps

Personal-use (NOT Play Store) AI voice-copilot navigation app: Kotlin/Jetpack Compose front end + Python/FastAPI backend + Google Routes/Places APIs + a SumoPod/DeepSeek LLM copilot. Runs on the developer's own phone only.

## Layout

- `android/` — the Android app (`com.virtueson.copilotmaps`). MVVM, Jetpack Compose, manual DI via `ViewModelProvider.Factory` (no Hilt). Retrofit + Moshi.
- `backend/` — FastAPI service (`/routes/plan`, `/places/search`, `/copilot/ask`, `/health`). Provider pattern: each capability has a stub + a real (Google/LLM) impl selected by env var.
- `docs/superpowers/{specs,plans}/` — one design spec + one implementation plan per feature.
- `.superpowers/sdd/progress.md` — the SDD progress ledger (git-ignored scratch).

## Build, test, run

**Backend** (a `.venv` lives under `backend/`):
```bash
cd backend
./.venv/Scripts/python.exe -m pytest -q                       # tests (force stubs via dependency_overrides — never hit live Google/LLM)
./.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000  # run server
```

**Android** — `java` is NOT on PATH; set JAVA_HOME to Android Studio's JBR first (PowerShell):
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat testDebugUnitTest --console=plain     # unit tests
.\gradlew.bat compileDebugKotlin --console=plain    # quick compile check
.\gradlew.bat installDebug --console=plain           # build + install to the connected device
```

**On-device run:** start the backend, then `adb reverse tcp:8000 tcp:8000` so the phone reaches `http://localhost:8000/`. `adb` is at `C:\Users\Russell\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

## Conventions

- **Pure decider pattern:** put navigation/announcement/geometry logic in pure functions (no Android imports) so they're unit-testable on the plain JVM. UI/Android glue stays thin around them. Examples: `data/NavAnnouncer.kt`, `data/NavProgress.kt`, `data/OffRoute.kt`, `data/AppLanguage.kt`.
- **Android unit tests are plain JVM JUnit** — no `Context`/`SharedPreferences`/framework types in unit-tested code paths. Android-only classes are verified by compilation + on-device testing.
- **Backend:** Pydantic v2 models in `app/models.py`; providers under `app/{planners,places,copilot}/`; the copilot uses a provider-agnostic tool registry (`app/copilot/registry.py` + `build_tools()`), so a capability is declared once.
- **Feature flow:** brainstorm → spec → plan → subagent-driven development (fresh implementer per task, per-task review, whole-branch review) → finish → push. Stay on `master` (no PRs).

## Hard rules

- **NEVER commit `backend/.env` or `local.properties`** — they hold API keys (Google Routes/Places, Android Maps, SumoPod `MODEL_API`) and are git-ignored. Confirm `git status` excludes them before every commit; never stage scratch files (`test_output.txt`, screenshots, anything under `.superpowers/`).
- Only push to `origin/master` after work is reviewed and (for app changes) verified on device.
- Bleeding-edge toolchain: AGP 9.x, Kotlin 2.2.x, Compose BOM 2026.x — prefer solutions that don't depend on plugins lagging AGP 9.
