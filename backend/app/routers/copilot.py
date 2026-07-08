import time

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException

from app.config import get_copilot, get_settings
from app.copilot.base import CopilotError
from app.copilot.language import detect_language
from app.copilot.run_log import build_run_row, log_run
from app.models import CopilotAskRequest, CopilotAskResponse

router = APIRouter()


@router.post("/copilot/ask", response_model=CopilotAskResponse)
async def copilot_ask(
    request: CopilotAskRequest,
    background_tasks: BackgroundTasks,
    copilot=Depends(get_copilot),
    settings=Depends(get_settings),
) -> CopilotAskResponse:
    t0 = time.monotonic()
    try:
        result = await copilot.ask(request.messages, request.context)
    except CopilotError as exc:
        latency_ms = int((time.monotonic() - t0) * 1000)
        # FastAPI drops background tasks when the endpoint raises HTTPException,
        # so log inline (before raising) rather than via background_tasks.add_task.
        # log_run swallows all exceptions and has its own 5s timeout, and latency
        # doesn't matter here since the request has already failed.
        await log_run(build_run_row(
            session_id=request.session_id, turn_index=request.turn_index,
            messages=request.messages, context=request.context, result=None,
            answer_language=None, model=settings.model_name,
            provider=settings.copilot_provider, latency_ms=latency_ms, error=str(exc),
        ))
        raise HTTPException(status_code=502, detail=f"Copilot error: {exc}") from exc

    latency_ms = int((time.monotonic() - t0) * 1000)
    language = detect_language(result.reply)
    background_tasks.add_task(log_run, build_run_row(
        session_id=request.session_id, turn_index=request.turn_index,
        messages=request.messages, context=request.context, result=result,
        answer_language=language, model=settings.model_name,
        provider=settings.copilot_provider, latency_ms=latency_ms, error=None,
    ))
    return CopilotAskResponse(reply=result.reply, language=language)
