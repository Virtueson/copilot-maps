from app.planners.google import _parse_steps


def test_parse_steps_extracts_fields_and_defaults_missing():
    item = {
        "legs": [
            {
                "steps": [
                    {
                        "navigationInstruction": {
                            "instructions": "Turn left onto Jl. Sudirman",
                            "maneuver": "TURN_LEFT",
                        },
                        "distanceMeters": 250,
                        "startLocation": {"latLng": {"latitude": -6.2, "longitude": 106.8}},
                    },
                    {
                        # navigationInstruction omitted (Google does this for some steps)
                        "distanceMeters": 100,
                        "startLocation": {"latLng": {"latitude": -6.21, "longitude": 106.81}},
                    },
                ]
            }
        ]
    }
    steps = _parse_steps(item)
    assert len(steps) == 2
    assert steps[0].instruction == "Turn left onto Jl. Sudirman"
    assert steps[0].maneuver == "TURN_LEFT"
    assert steps[0].distance_meters == 250
    assert steps[0].location.lat == -6.2
    assert steps[0].location.lng == 106.8
    assert steps[1].instruction == ""
    assert steps[1].maneuver == ""
    assert steps[1].distance_meters == 100


def test_parse_steps_no_legs_returns_empty():
    assert _parse_steps({}) == []
