from fastapi import APIRouter, Depends, HTTPException

from app.config import get_copilot
from app.copilot.base import CopilotError
from app.copilot.language import detect_language
from app.models import CopilotAskRequest, CopilotAskResponse

router = APIRouter()


@router.post("/copilot/ask", response_model=CopilotAskResponse)
async def copilot_ask(
    request: CopilotAskRequest,
    copilot=Depends(get_copilot),
) -> CopilotAskResponse:
    try:
        reply = await copilot.ask(request.messages, request.context)
    except CopilotError as exc:
        raise HTTPException(status_code=502, detail=f"Copilot error: {exc}") from exc
    return CopilotAskResponse(reply=reply, language=detect_language(reply))
