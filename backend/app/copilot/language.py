"""Tiny, dependency-free Indonesian-vs-English detector for spoken Copilot replies.

Only two languages ship, so a high-frequency Indonesian marker-word count is enough.
Returns "id" when Indonesian markers clearly dominate, else "en" (the safe default).
"""

import re

_ID_MARKERS = {
    "yang", "tidak", "ada", "dari", "ke", "di", "dan", "atau", "dengan",
    "belok", "kanan", "kiri", "lurus", "terus", "menit", "kilometer", "meter",
    "jalan", "sampai", "tujuan", "macet", "lewat", "menuju", "sekitar", "depan",
    "anda", "saya", "ya", "tidak", "sudah", "belum", "akan",
}


def detect_language(text: str) -> str:
    words = re.findall(r"[a-zA-Z]+", text.lower())
    if not words:
        return "en"
    hits = sum(1 for w in words if w in _ID_MARKERS)
    # Require at least 2 marker hits, or 1 hit in a very short reply, to claim Indonesian.
    if hits >= 2 or (hits >= 1 and len(words) <= 3):
        return "id"
    return "en"
