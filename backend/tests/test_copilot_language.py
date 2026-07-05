from app.copilot.language import detect_language


def test_detects_indonesian():
    assert detect_language("Belok kiri di jalan berikutnya lalu lurus") == "id"


def test_detects_english():
    assert detect_language("Turn left at the next street then go straight") == "en"


def test_empty_defaults_to_english():
    assert detect_language("") == "en"


def test_ambiguous_short_defaults_to_english():
    assert detect_language("OK") == "en"
