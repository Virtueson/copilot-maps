from app.copilot.language import detect_language


def test_detects_indonesian():
    assert detect_language("Belok kiri di jalan berikutnya lalu lurus") == "id"


def test_detects_english():
    assert detect_language("Turn left at the next street then go straight") == "en"


def test_empty_defaults_to_english():
    assert detect_language("") == "en"


def test_ambiguous_short_defaults_to_english():
    assert detect_language("OK") == "en"


def test_short_english_reply_with_incidental_marker_not_misclassified():
    # Regression: "meter"/"kilometer" used to be Indonesian markers, which caused
    # short English replies mentioning distances to be misclassified as "id".
    assert detect_language("About 500 meter") == "en"


def test_three_word_english_reply_with_no_markers():
    assert detect_language("Turn left now") == "en"


def test_short_genuinely_indonesian_reply_is_all_markers():
    assert detect_language("Belok kiri") == "id"
