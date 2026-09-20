"""Shared helpers for the end-to-end verification scripts.

Kept in one place because every script that drives the live API needs the same
envelope handling, the same login, and the same "read a listing back" calls —
and a second copy of that is a second place for it to drift out of step with
the server.
"""

import json
import urllib.error
import urllib.request

BASE = "http://localhost:8081/api"
PASSWORD = "Admin@123"

_CHECKS = []


class ApiFailure(AssertionError):
    """The server refused a call, or answered with a non-zero code."""


def call(method, path, token=None, body=None):
    """One API call, unwrapped. Raises ApiFailure on anything but code 0."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise ApiFailure(f"{method} {path} -> HTTP {e.code}: {e.read().decode('utf-8')}") from None
    if payload.get("code") != 0:
        raise ApiFailure(f"{method} {path} -> {payload.get('code')} {payload.get('message')}")
    return payload.get("data")


def login(username):
    return call("POST", "/auth/login",
                body={"username": username, "password": PASSWORD})["accessToken"]


def inventory(token):
    return call("GET", "/inventory-notes", token)


def note_of(token, note_id):
    for note in inventory(token):
        if str(note["id"]) == str(note_id):
            return note
    return None


def note_ids(token):
    return {str(n["id"]) for n in inventory(token)}


def listing_of(token, listing_id):
    for listing in call("GET", "/listings/mine", token):
        if str(listing["id"]) == str(listing_id):
            return listing
    return None


def pick_free_note(token, want):
    """The note with the most free goods, and how much can actually be listed.

    Sized to the account rather than fixed, so a check is repeatable against
    whatever stock the demo data left behind instead of needing a fresh
    database for every run.
    """
    notes = inventory(token)
    if not notes:
        raise ApiFailure("seller has no inventory notes to test with")
    best = max(notes, key=lambda n: float(n["availableQuantity"]))
    available = float(best["availableQuantity"])
    if available < 1:
        raise ApiFailure("seller has no free inventory to test with")
    return best, min(want, available)


def publish(token, note, quantity, confirm_mode, price=68000):
    return call("POST", "/listings", token, {
        "side": "SELL",
        "inventoryNoteId": note["id"],
        "categoryId": note["categoryId"],
        "commodityName": note["commodityName"],
        "quantity": quantity,
        "unit": note["unit"],
        "price": price,
        "priceType": "FIXED",
        "confirmMode": confirm_mode,
        "warehouseId": note["warehouseId"],
        "deliveryMethod": "SELF_PICKUP",
        # Far enough out that nothing expires mid-check unless the check is
        # specifically about expiry.
        "validUntil": "2027-12-31T23:59:59+08:00",
    })


def check(label, ok, detail=""):
    _CHECKS.append((label, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + label + (f"  [{detail}]" if detail else ""))
    return ok


def check_all(pairs):
    for label, ok, *rest in pairs:
        check(label, ok, rest[0] if rest else "")


def report():
    """Prints the tally and exits non-zero if anything failed."""
    failed = [c for c in _CHECKS if not c[1]]
    print(f"\n{len(_CHECKS) - len(failed)}/{len(_CHECKS)} checks passed")
    for label, _, detail in failed:
        print(f"  FAILED: {label} {detail}")
    if failed:
        raise SystemExit(1)
