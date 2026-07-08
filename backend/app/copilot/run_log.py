"""Best-effort audit logging of Copilot runs to Supabase (PostgREST).

`build_run_row` is pure (assembles the row). `log_run` is fire-and-forget: it
returns immediately when Supabase is unconfigured, and swallows any POST failure
(logging a warning that names the run) so logging can never break the Copilot.
"""

import logging

import httpx

from app.config import get_settings

logger = logging.getLogger("copilot.run_log")


def build_run_row(
    session_id, turn_index, messages, context, result, answer_language,
    model, provider, latency_ms, error,
) -> dict:
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    return {
        "session_id": session_id,
        "turn_index": turn_index,
        "user_message": last_user,
        "memory_messages": [{"role": m.role, "content": m.content} for m in messages],
        "trip_context": {
            "origin": {"lat": context.origin.lat, "lng": context.origin.lng},
            "selected_route_polyline": context.selected_route_polyline,
            "routes": [
                {
                    "summary": r.summary,
                    "distance_meters": r.distance_meters,
                    "duration_seconds": r.duration_seconds,
                    "traffic": r.traffic,
                    "selected": r.selected,
                }
                for r in context.routes
            ],
        },
        "tools_used": result.tools_used if result else [],
        "loop_count": result.loop_count if result else None,
        "answer": result.reply if result else "",
        "answer_language": answer_language,
        "model": model,
        "provider": provider,
        "latency_ms": latency_ms,
        "error": error,
    }


async def log_run(row: dict, client=None) -> None:
    settings = get_settings()
    if not (settings.supabase_url and settings.supabase_service_key):
        return  # logging disabled — silent no-op

    owns_client = client is None
    client = client or httpx.AsyncClient(timeout=5.0)
    try:
        resp = await client.post(
            f"{settings.supabase_url}/rest/v1/copilot_runs",
            json=row,
            headers={
                "apikey": settings.supabase_service_key,
                "Authorization": f"Bearer {settings.supabase_service_key}",
                "Content-Type": "application/json",
                "Prefer": "return=minimal",
            },
        )
        resp.raise_for_status()
    except Exception as e:  # never let logging break the request
        logger.warning(
            "copilot run logging FAILED session=%s turn=%s q=%r: %s",
            row.get("session_id"), row.get("turn_index"),
            (row.get("user_message") or "")[:40], e,
        )
    finally:
        if owns_client:
            await client.aclose()
