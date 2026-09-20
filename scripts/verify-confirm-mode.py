"""End-to-end check of the two listing confirmation modes.

  挂牌即要约 (AUTO)    — accepting the listing forms the contract; goods move at once.
  摘牌待确认 (MANUAL)  — accepting only reserves; the goods wait for the lister.

Both exist because both are real market conventions, and the difference is
precisely where title moves. So that is what this checks, at every step:

  1. MANUAL: accept         -> goods must NOT move; listing remaining drops
  2. MANUAL: lister confirms -> goods move; buyer gains a note
  3. MANUAL: lister rejects  -> goods restored to the listing; buyer gets nothing
  4. withdrawal while an acceptance waits -> refused
  5. AUTO: accept            -> goods move immediately, no waiting state
  6. MANUAL on a BUY listing -> refused

Run against a live backend:  python scripts/verify-confirm-mode.py
"""

from spotlink_api import (
    ApiFailure,
    call,
    check,
    listing_of,
    login,
    note_ids,
    note_of,
    pick_free_note,
    publish,
    report,
)


def main():
    seller = login("seller01")
    buyer = login("buyer01")

    # ---- 1. MANUAL: accepting must not move goods -------------------------
    print("\n[1] MANUAL listing, buyer accepts")
    note, qty = pick_free_note(seller, 30)
    before = note_of(seller, note["id"])
    listing = publish(seller, note, qty, "MANUAL")
    check("listing records the mode", listing["confirmMode"] == "MANUAL", listing["confirmModeText"])
    check("market row shows the mode to buyers",
          listing_of(seller, listing["id"])["confirmModeText"] == "需挂牌方确认")

    after_publish = note_of(seller, note["id"])
    check(f"publishing froze {qty:g}",
          float(after_publish["frozenQuantity"]) - float(before["frozenQuantity"]) == qty,
          f"{before['frozenQuantity']} -> {after_publish['frozenQuantity']}")

    buyer_notes_before = note_ids(buyer)
    order = call("POST", f"/listings/{listing['id']}/accept", buyer, {"quantity": qty})
    check("order waits for the lister", order["status"] == "PENDING_CONFIRM", order["statusText"])
    check("order carries a deadline", order["confirmDeadline"] is not None)
    check("buyer was NOT given a note", note_ids(buyer) == buyer_notes_before)

    after_accept = note_of(seller, note["id"])
    check("seller's goods did not move",
          float(after_accept["frozenQuantity"]) == float(after_publish["frozenQuantity"])
          and float(after_accept["totalQuantity"]) == float(after_publish["totalQuantity"]),
          f"total={after_accept['totalQuantity']} frozen={after_accept['frozenQuantity']}")
    check("listing remaining dropped to 0",
          float(listing_of(seller, listing["id"])["remainingQuantity"]) == 0)
    check("buyer's actions exclude CONFIRMED",
          "CONFIRMED" not in order["allowedActions"], str(order["allowedActions"]))

    seller_view = call("GET", f"/orders/{order['id']}", seller)
    check("seller's actions include CONFIRMED",
          "CONFIRMED" in seller_view["allowedActions"], str(seller_view["allowedActions"]))

    try:
        call("POST", f"/orders/{order['id']}/confirm", buyer)
        check("buyer cannot confirm for the seller", False, "the server allowed it")
    except ApiFailure as e:
        check("buyer cannot confirm for the seller", "只有挂牌方" in str(e), str(e)[:70])

    # ---- 2. lister confirms -> goods move ---------------------------------
    print("\n[2] lister confirms")
    confirmed = call("POST", f"/orders/{order['id']}/confirm", seller)
    check("order is CONFIRMED", confirmed["status"] == "CONFIRMED", confirmed["statusText"])
    check("deadline cleared", confirmed["confirmDeadline"] is None)
    check("buyer received a note", len(note_ids(buyer) - buyer_notes_before) == 1)
    after_confirm = note_of(seller, note["id"])
    check(f"seller's total dropped by {qty:g}",
          float(after_accept["totalQuantity"]) - float(after_confirm["totalQuantity"]) == qty,
          f"{after_accept['totalQuantity']} -> {after_confirm['totalQuantity']}")

    # ---- 3. MANUAL: accept then reject ------------------------------------
    print("\n[3] MANUAL listing, buyer accepts, lister rejects")
    note2, qty2 = pick_free_note(seller, 20)
    before2 = note_of(seller, note2["id"])
    listing2 = publish(seller, note2, qty2, "MANUAL")
    order2 = call("POST", f"/listings/{listing2['id']}/accept", buyer, {"quantity": qty2})
    check("listing remaining dropped to 0",
          float(listing_of(seller, listing2["id"])["remainingQuantity"]) == 0)

    buyer_notes_before2 = note_ids(buyer)
    rejected = call("POST", f"/orders/{order2['id']}/reject", seller, {"reason": "价格需要复核"})
    check("order is CANCELLED", rejected["status"] == "CANCELLED", rejected["statusText"])
    check("buyer got nothing", note_ids(buyer) == buyer_notes_before2)
    check(f"listing reopened to {qty2:g}",
          float(listing_of(seller, listing2["id"])["remainingQuantity"]) == qty2)
    check("listing status is OPEN again",
          listing_of(seller, listing2["id"])["status"] == "OPEN")
    after_reject = note_of(seller, note2["id"])
    check("seller's goods untouched",
          float(after_reject["totalQuantity"]) == float(before2["totalQuantity"]),
          f"total={after_reject['totalQuantity']}")

    # ---- 4. withdrawing while an acceptance waits -------------------------
    print("\n[4] withdrawal while an acceptance waits")
    order3 = call("POST", f"/listings/{listing2['id']}/accept", buyer, {"quantity": qty2})
    try:
        call("POST", f"/listings/{listing2['id']}/close", seller)
        check("withdrawal refused while waiting", False, "the server allowed it")
    except ApiFailure as e:
        check("withdrawal refused while waiting", "等待您确认" in str(e), str(e)[:70])
    call("POST", f"/orders/{order3['id']}/reject", seller, {"reason": "清理测试数据"})
    call("POST", f"/listings/{listing2['id']}/close", seller)
    check("withdrawal works once settled", True)

    # ---- 5. AUTO still closes at acceptance -------------------------------
    print("\n[5] AUTO listing, buyer accepts")
    note3, qty3 = pick_free_note(seller, 15)
    listing3 = publish(seller, note3, qty3, "AUTO")
    check("listing records the mode", listing3["confirmMode"] == "AUTO", listing3["confirmModeText"])
    buyer_notes_before3 = note_ids(buyer)
    order4 = call("POST", f"/listings/{listing3['id']}/accept", buyer, {"quantity": qty3})
    check("order is CONFIRMED immediately", order4["status"] == "CONFIRMED", order4["statusText"])
    check("buyer received a note at acceptance", len(note_ids(buyer) - buyer_notes_before3) == 1)
    check("no deadline on an immediate trade", order4["confirmDeadline"] is None)

    # ---- 6. MANUAL is refused on a BUY listing ----------------------------
    print("\n[6] MANUAL on a BUY listing")
    try:
        call("POST", "/listings", buyer, {
            "side": "BUY", "categoryId": note3["categoryId"],
            "commodityName": note3["commodityName"], "quantity": 10, "price": 60000,
            "priceType": "FIXED", "confirmMode": "MANUAL",
            "validUntil": "2027-12-31T23:59:59+08:00",
        })
        check("BUY + MANUAL refused", False, "the server allowed it")
    except ApiFailure as e:
        check("BUY + MANUAL refused", "不支持" in str(e), str(e)[:70])

    report()


if __name__ == "__main__":
    main()
