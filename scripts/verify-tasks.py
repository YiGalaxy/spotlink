"""End-to-end check of the task list and its push channel.

The reported bug was a seller who had to reload to discover a buyer had
accepted. Two things have to hold for that to be fixed, and both are checked
here against a live backend:

  1. the task list says what is pending, and says it to the right party only
  2. the SSE stream pushes to that party the moment it changes

Run:  python scripts/verify-tasks.py
"""

import json
import urllib.parse
import threading
import time
import urllib.request

from spotlink_api import (
    call,
    check,
    check_all,
    login,
    pick_free_note,
    publish,
    report,
)

BASE = "http://localhost:8081/api"


def tasks_of(token):
    return call("GET", "/tasks", token)


def kinds(tasks):
    return {t["kind"] for t in tasks}


def _thread(target, *args):
    """Starts a daemon reader thread."""
    thread = threading.Thread(target=target, args=args)
    thread.daemon = True
    thread.start()
    return thread


def open_stream(token, received, ready, stop):
    """Consumes /tasks/stream on a thread and records the events it sees."""
    req = urllib.request.Request(
        f"{BASE}/tasks/stream?token={urllib.parse.quote(token)}")
    req.add_header("Accept", "text/event-stream")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            ready.set()
            for raw in resp:
                if stop.is_set():
                    return
                line = raw.decode("utf-8").strip()
                if line.startswith("event:"):
                    received.append(line.split(":", 1)[1].strip())
    except Exception:
        # A closed stream is the normal end of this test, not a failure.
        return


def main():
    seller = login("seller01")
    buyer = login("buyer01")

    # ---- 1. the list itself -------------------------------------------------
    print("\n[1] task list")
    seller_tasks = tasks_of(seller)
    check("seller gets a list", isinstance(seller_tasks, list), f"{len(seller_tasks)} 项")

    # A MANUAL listing leaves an acceptance waiting for the lister, so the
    # seller must gain a task and the buyer must not gain that same one.
    note, qty = pick_free_note(seller, 10)
    listing = publish(seller, note, qty, "MANUAL")
    order = call("POST", f"/listings/{listing['id']}/accept", buyer, {"quantity": qty})

    seller_after = tasks_of(seller)
    buyer_after = tasks_of(buyer)

    seller_acceptance = [t for t in seller_after if t["kind"] == "ACCEPTANCE_PENDING"]
    buyer_acceptance = [t for t in buyer_after if t["kind"] == "ACCEPTANCE_PENDING"]

    check("lister is told to answer", len(seller_acceptance) >= 1,
          f"{len(seller_acceptance)} 项待确认")
    check("counterparty is NOT told to answer", len(buyer_acceptance) == 0,
          f"{len(buyer_acceptance)} 项")
    if seller_acceptance:
        task = seller_acceptance[0]
        check("task names the order", task["targetNo"] == order["orderNo"], task["targetNo"])
        check("task carries a deadline", task["deadline"] is not None)
        check("task names the counterparty", bool(task["counterparty"]), task["counterparty"])
        check("task names the action", bool(task["action"]), task["action"])

    # ---- 2. the push --------------------------------------------------------
    # The stream stays open from here to the end: closing it mid-test was a bug
    # in an earlier version of this script, which then reported a product
    # failure that was really the reader having been told to stop.
    print("\n[2] push")
    seller_events, seller_ready, seller_stop = [], threading.Event(), threading.Event()
    _thread(open_stream, seller, seller_events, seller_ready, seller_stop)
    seller_ready.wait(timeout=10)
    check("stream opened", seller_ready.is_set())
    time.sleep(1)
    check("stream greets the client", "connected" in seller_events, str(seller_events))

    before = len(seller_events)
    call("POST", f"/orders/{order['id']}/confirm", seller)
    time.sleep(2)
    check("a change pushes to the lister", len(seller_events) > before,
          f"{before} -> {len(seller_events)}")

    # ---- 3. the push is scoped to one enterprise ---------------------------
    print("\n[3] the stream is per-enterprise")
    buyer_events, buyer_ready, buyer_stop = [], threading.Event(), threading.Event()
    _thread(open_stream, buyer, buyer_events, buyer_ready, buyer_stop)
    buyer_ready.wait(timeout=10)

    # A platform operator has no tenant. Their stream must never carry another
    # company's events — the same boundary every other read enforces, applied
    # to the one channel that does not go through a query.
    operator = login("admin")
    operator_events, operator_ready, operator_stop = [], threading.Event(), threading.Event()
    _thread(open_stream, operator, operator_events, operator_ready, operator_stop)
    operator_ready.wait(timeout=10)
    time.sleep(1)

    seller_before = len(seller_events)
    buyer_before = len(buyer_events)
    operator_before = len(operator_events)

    note2, qty2 = pick_free_note(seller, 10)
    listing2 = publish(seller, note2, qty2, "MANUAL")
    order2 = call("POST", f"/listings/{listing2['id']}/accept", buyer, {"quantity": qty2})
    time.sleep(2)

    check("the lister hears", len(seller_events) > seller_before,
          f"{seller_before} -> {len(seller_events)}")
    check("the accepting party hears too", len(buyer_events) > buyer_before,
          f"{buyer_before} -> {len(buyer_events)}")
    check("an operator with no tenant hears nothing",
          len(operator_events) == operator_before,
          f"{operator_before} -> {len(operator_events)}")

    call("POST", f"/orders/{order2['id']}/reject", seller, {"reason": "测试收尾"})
    for stop in (seller_stop, buyer_stop, operator_stop):
        stop.set()

    # ---- 4. acting clears the task -----------------------------------------
    print("\n[4] acting on a task removes it")
    remaining = [t for t in tasks_of(seller) if t["kind"] == "ACCEPTANCE_PENDING"]
    check("the answered acceptance is gone from the seller's list", len(remaining) == 0,
          f"{len(remaining)} 项")

    report()


if __name__ == "__main__":
    main()
