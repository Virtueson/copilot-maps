# Copilot Maps — Project Brief

## The idea
An AI navigation app for Android. While driving, you ask in plain language and a voice copilot answers from live route data. Examples:
- "Which road is jammed?"
- "Is there a gas station on my route?"
- "Any good restaurants on the way?"
- "I see 3 routes — which one should I take?"
- "Why did I miss that turn?"

It's Google Maps + a talking AI assistant that actually understands your trip.

## My background
- AI / backend engineer. Comfortable with backend services and LLM/agent work.
- Basic frontend. **No Android experience** — this is my first Android app.
- So: the backend and the AI agent are easy for me; the Android side is the new, unfamiliar part. Explain Android steps clearly.

## How it's split
Two parts. The phone does only what must be on a phone; everything smart lives in a backend I write.

```
ANDROID APP                 MY BACKEND (Python)            EXTERNAL APIs
- show map                  - /routes/plan        ───►     Google Routes API
- live GPS                  - /places/along-route ───►     Google Places API
- mic + speaker (voice)     - /copilot/ask (LLM agent) ─►  Anthropic / LLM
- draw route + traffic
            ◄── HTTPS ──►
```

API keys stay on the backend. The copilot endpoint is just an LLM agent loop with tools (compare routes, find places, explain traffic).

## Tools / stack

**Android app**
- Kotlin + Jetpack Compose (UI)
- Google Maps SDK for Android (`maps-compose`) — map display
- FusedLocationProvider — live GPS
- Retrofit — call my backend
- Android SpeechRecognizer (voice in) + TextToSpeech (voice out)

**Backend (my comfort zone)**
- FastAPI (Python)
- Google Routes API — routes + per-segment traffic
- Google Places API — gas stations, restaurants on route
- An LLM (Anthropic) with tool-calling for the copilot

**Keys needed**
- 1 Android-restricted key (map display, in the app)
- Server-side keys for Routes / Places / LLM (in the backend)

## Build order
1. **Map + GPS** — Android Studio project, show a map with my live location. (Proves the toolchain works.)
2. **Routing** — backend `/routes/plan` → Routes API; app draws the route, colors traffic, shows the 3 alternates with ETA.
3. **Places** — backend `/places/along-route`; drop gas/restaurant markers on the route.
4. **Copilot** — backend `/copilot/ask` agent loop; text chat in the app first, then add voice (STT in, TTS out).
5. **Live nav** — follow GPS along the route, detect missed turns, background location service.

Build in this order. The AI copilot (step 4) is the fun part for me, but steps 1–2 (GPS + routing reliability) are the hard plumbing — get those solid first. Tip: stub `/routes/plan` with fake route data early so the app and backend can be built in parallel.
