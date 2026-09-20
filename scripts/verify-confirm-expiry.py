"""Checks that an unanswered acceptance lapses on its own.

Silence from the lister must not freeze an offer forever, so the sweep cancels
the order and puts the goods back. Verifying this needs a short window, which
means the backend has to be running with overrides — the sweep only fires every
five minutes by default:

    BULK_CONFIRM_WINDOW=PT30S mvn spring-boot:run \\
      -Dspring-boot.run.arguments="--bulk.trading.sweep-interval-ms=15000 --bulk.trading.sweep-initial-delay-ms=5000"

Run:  python scripts/verify-confirm-expiry.py
"""

import time

from spotlink_api import (
    call,
    check_all,
    listing_of,
    login,
    note_of,
    pick_free_note,
    publish,
    report,
)

WAIT_SECONDS = 75


def main():
    seller = login("seller01")
    buyer = login("buyer01")

    note, qty = pick_free_note(seller, 20)
    before = note_of(seller, note["id"])
    listing = publish(seller, note, qty, "MANUAL")
    order = call("POST", f"/listings/{listing['id']}/accept", buyer, {"quantity": qty})

    print(f"order {order['orderNo']} waiting; deadline {order['confirmDeadline']}")
    print(f"waiting {WAIT_SECONDS}s for the sweep to lapse it...")
    time.sleep(WAIT_SECONDS)

    after = call("GET", f"/orders/{order['id']}", seller)
    listing_after = listing_of(seller, listing["id"])
    note_after = note_of(seller, note["id"])

    check_all([
        ("order lapsed to CANCELLED", after["status"] == "CANCELLED", after["statusText"]),
        ("reason blames the silence, not the buyer",
         "未在期限内确认" in (after["cancelReason"] or ""), after["cancelReason"] or ""),
        ("deadline cleared", after["confirmDeadline"] is None),
        (f"listing reopened to {qty:g}",
         float(listing_after["remainingQuantity"]) == qty,
         str(listing_after["remainingQuantity"])),
        ("listing is OPEN again", listing_after["status"] == "OPEN", listing_after["statusText"]),
        ("seller's goods untouched",
         float(note_after["totalQuantity"]) == float(before["totalQuantity"]),
         f"{before['totalQuantity']} -> {note_after['totalQuantity']}"),
    ])
    report()


if __name__ == "__main__":
    main()
