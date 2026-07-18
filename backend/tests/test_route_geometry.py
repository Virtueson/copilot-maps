import polyline

from app.models import LatLng, Place
from app.places.route_geometry import filter_ahead_on_route

# Straight west->east line along the equator: (0,0) -> (0,0.1).
# At the equator 0.1 deg lng ~= 11.1 km; 0.001 deg ~= 111 m; cos(0) = 1.
ROUTE = polyline.encode([(0.0, 0.0), (0.0, 0.05), (0.0, 0.1)])
ORIGIN = LatLng(lat=0.0, lng=0.02)  # ~2.2 km along the route


def _place(name, lat, lng):
    return Place(id=name, name=name, lat=lat, lng=lng)


def _names(places):
    return [p.name for p in places]


def test_drops_place_behind_origin():
    behind = _place("Behind", 0.0, 0.01)  # ~1.1 km along, before the origin
    assert filter_ahead_on_route(ORIGIN, ROUTE, [behind]) == []


def test_keeps_place_ahead_on_route():
    ahead = _place("Ahead", 0.0, 0.05)  # ~5.6 km along, on the line
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [ahead])) == ["Ahead"]


def test_drops_place_far_off_route():
    off = _place("Off", 0.02, 0.06)  # ahead but ~2.2 km to the side (> 1 km)
    assert filter_ahead_on_route(ORIGIN, ROUTE, [off]) == []


def test_keeps_place_just_inside_corridor():
    near = _place("Near", 0.005, 0.04)  # ahead, ~557 m to the side (< 1 km)
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [near])) == ["Near"]


def test_sorts_nearest_ahead_first():
    far = _place("Far", 0.0, 0.08)
    near = _place("Near", 0.0, 0.04)
    result = filter_ahead_on_route(ORIGIN, ROUTE, [far, near])
    assert _names(result) == ["Near", "Far"]


def test_keeps_place_at_origin_within_grace():
    here = _place("Here", 0.0, 0.02)  # exactly at the origin's progress point
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [here])) == ["Here"]


def test_passthrough_when_polyline_undecodable():
    # A malformed polyline must never crash a search; it decodes to nothing,
    # so filtering is skipped and the input is returned unchanged.
    p = _place("Anything", 9.9, 9.9)
    assert _names(filter_ahead_on_route(ORIGIN, "abc", [p])) == ["Anything"]


def test_corridor_override_widens_acceptance():
    # ~2.2 km off to the side: dropped at the default 1 km corridor, kept at 3 km.
    off = _place("Off", 0.02, 0.06)
    assert filter_ahead_on_route(ORIGIN, ROUTE, [off]) == []
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [off], corridor_m=3000)) == ["Off"]
