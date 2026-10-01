#!/usr/bin/env python3
"""
burst.py — On-sale stampede simulator for Seat Reservation Service.

Usage:
    python3 burst.py <BASE_URL>
    python3 burst.py http://localhost:8080

Runs the following tests:
  1. Creates a show with 50 seats.
  2. Hot-seat storm: 200 users fight for one seat (A1). Exactly 1 wins.
  3. Spread storm: 100 users each grab a random available seat.
  4. Per-user limit test: one user fires 10 parallel reserves (limit=4).
  5. Idempotency test: same key retried 50 times → one reservation.
  6. Same key, different seats → 409.
  7. Reconciliation: available + held + confirmed == total_seats.
"""

import asyncio
import aiohttp
import json
import sys
import time
import uuid
import random

BASE_URL = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN_TOKEN = "admin-secret"
CONCURRENCY = 50

# ─── Results tracking ───
results = {
    "confirmed": 0,
    "seat_taken": 0,
    "per_user_limit": 0,
    "idempotent_replay": 0,
    "idempotent_conflict": 0,
    "not_found": 0,
    "other_4xx": 0,
    "5xx": 0,
    "errors": 0,
}


async def create_show(session, name, seats, price_paise=25000, per_user_limit=4):
    """Create a show via admin endpoint."""
    url = f"{BASE_URL}/shows"
    payload = {
        "name": name,
        "seats": seats,
        "price_paise": price_paise,
        "per_user_limit": per_user_limit,
    }
    async with session.post(
        url,
        json=payload,
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
    ) as resp:
        data = await resp.json()
        assert resp.status == 201, f"Create show failed: {resp.status} {data}"
        return data


semaphore = None

async def reserve(session, show_id, user_id, seats, idempotency_key=None):
    """Reserve seat(s) with retry on 5xx."""
    url = f"{BASE_URL}/shows/{show_id}/reserve"
    key = idempotency_key or str(uuid.uuid4())
    payload = {"seats": seats, "idempotency_key": key}
    for attempt in range(3):
        try:
            async with semaphore:
                async with session.post(
                    url,
                    json=payload,
                    headers={"Authorization": f"Bearer {user_id}"},
                    timeout=aiohttp.ClientTimeout(total=60),
                ) as resp:
                    body = await resp.json()
                    if resp.status < 500:
                        return resp.status, body
                    # 5xx — retry after short delay
                    await asyncio.sleep(1)
        except Exception as e:
            if attempt == 2:
                return 0, {"error": str(e)}
            await asyncio.sleep(1)
    return 500, {"error": "max retries exceeded"}


async def get_show(session, show_id):
    """Fetch show state with retry."""
    url = f"{BASE_URL}/shows/{show_id}"
    for attempt in range(5):
        try:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=30)) as resp:
                if resp.status == 200:
                    return await resp.json()
                await asyncio.sleep(2)
        except Exception:
            await asyncio.sleep(2)
    raise Exception(f"Failed to fetch show {show_id} after retries")


def classify(status, body):
    """Classify a response into a result bucket."""
    if status == 201:
        results["confirmed"] += 1
        return "confirmed"
    elif status == 200:
        results["idempotent_replay"] += 1
        return "idempotent_replay"
    elif status == 409:
        error = body.get("error", "")
        if error == "seat_taken":
            results["seat_taken"] += 1
        elif error == "per_user_limit":
            results["per_user_limit"] += 1
        elif error == "idempotency_conflict":
            results["idempotent_conflict"] += 1
        else:
            results["other_4xx"] += 1
        return error
    elif status == 404:
        results["not_found"] += 1
        return "not_found"
    elif 400 <= status < 500:
        results["other_4xx"] += 1
        return "other_4xx"
    elif status >= 500:
        results["5xx"] += 1
        return "5xx"
    else:
        results["errors"] += 1
        return "error"


def print_header(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")


async def main():
    connector = aiohttp.TCPConnector(limit=200)
    async with aiohttp.ClientSession(connector=connector) as session:

        global semaphore
        semaphore = asyncio.Semaphore(CONCURRENCY)

        # ── Health check ──
        print(f"Target: {BASE_URL}")
        try:
            async with session.get(f"{BASE_URL}/actuator/health/readiness") as resp:
                if resp.status != 200:
                    print(f"⚠ Readiness check returned {resp.status}")
                else:
                    print("✓ Service is ready")
        except Exception as e:
            print(f"✗ Cannot reach service: {e}")
            return

        # ── Generate seat names ──
        rows = "ABCDEFGHIJ"
        seat_names = [f"{r}{c}" for r in rows for c in range(1, 6)]  # 50 seats

        # ══════════════════════════════════════════════════════════
        # TEST 1: Hot-seat storm — 200 users, same seat (A1)
        # ══════════════════════════════════════════════════════════
        print_header("TEST 1: Hot-Seat Storm (200 users → seat A1)")
        for k in results:
            results[k] = 0

        show = await create_show(session, f"hotseat-{uuid.uuid4().hex[:8]}", seat_names)
        show_id = show["id"]

        tasks = []
        for i in range(200):
            user = f"user-hot-{i}"
            tasks.append(reserve(session, show_id, user, ["A1"]))

        t0 = time.monotonic()
        responses = await asyncio.gather(*tasks)
        elapsed = time.monotonic() - t0

        for status, body in responses:
            classify(status, body)

        print(f"  Duration:   {elapsed:.2f}s")
        print(f"  Confirmed:  {results['confirmed']}  (expected: 1)")
        print(f"  Seat taken: {results['seat_taken']}  (expected: 199)")
        print(f"  5xx:        {results['5xx']}  (expected: 0)")
        assert results["confirmed"] == 1, f"FAIL: {results['confirmed']} confirmations!"
        assert results["5xx"] == 0, f"FAIL: {results['5xx']} server errors!"
        print("  ✓ PASSED")

        # ── Reconciliation check ──
        state = await get_show(session, show_id)
        avail = state["available_count"]
        held = state["held_count"]
        conf = state["confirmed_count"]
        total = state["total_seats"]
        print(f"  Reconciliation: {avail} + {held} + {conf} = {avail+held+conf} (total={total})")
        assert avail + held + conf == total, "FAIL: reconciliation broken!"
        print("  ✓ Reconciliation holds")

        # ══════════════════════════════════════════════════════════
        # TEST 2: Spread storm — 200 users, random seats
        # ══════════════════════════════════════════════════════════
        print_header("TEST 2: Spread Storm (200 users → random seats)")
        for k in results:
            results[k] = 0

        show2 = await create_show(session, f"spread-{uuid.uuid4().hex[:8]}", seat_names)
        show_id2 = show2["id"]

        tasks = []
        for i in range(200):
            user = f"user-spread-{i}"
            seat = random.choice(seat_names)
            tasks.append(reserve(session, show_id2, user, [seat]))

        t0 = time.monotonic()
        responses = await asyncio.gather(*tasks)
        elapsed = time.monotonic() - t0

        for status, body in responses:
            classify(status, body)

        state2 = await get_show(session, show_id2)
        avail = state2["available_count"]
        held = state2["held_count"]
        conf = state2["confirmed_count"]
        total = state2["total_seats"]

        print(f"  Duration:      {elapsed:.2f}s")
        print(f"  Confirmed:     {results['confirmed']}")
        print(f"  Seat taken:    {results['seat_taken']}")
        print(f"  5xx:           {results['5xx']}  (expected: 0)")
        print(f"  Reconciliation: {avail} + {held} + {conf} = {avail+held+conf} (total={total})")
        assert avail + held + conf == total
        assert results["5xx"] == 0
        print("  ✓ PASSED")

        # ══════════════════════════════════════════════════════════
        # TEST 3: Per-user limit (limit=4, user fires 10 parallel)
        # ══════════════════════════════════════════════════════════
        print_header("TEST 3: Per-User Limit (10 parallel requests, limit=4)")
        for k in results:
            results[k] = 0

        show3 = await create_show(
            session, f"limit-{uuid.uuid4().hex[:8]}", seat_names, per_user_limit=4
        )
        show_id3 = show3["id"]
        limit_user = "user-limit-test"

        tasks = []
        for i in range(10):
            seat = seat_names[i]  # each request asks for a different seat
            tasks.append(reserve(session, show_id3, limit_user, [seat]))

        responses = await asyncio.gather(*tasks)
        for status, body in responses:
            classify(status, body)

        print(f"  Confirmed:      {results['confirmed']}  (expected: ≤4)")
        print(f"  Per-user limit: {results['per_user_limit']}  (expected: ≥6)")
        print(f"  5xx:            {results['5xx']}  (expected: 0)")
        assert results["confirmed"] <= 4, f"FAIL: {results['confirmed']} > limit 4"
        assert results["5xx"] == 0
        print("  ✓ PASSED")

        # ══════════════════════════════════════════════════════════
        # TEST 4: Idempotency — same key retried 50 times
        # ══════════════════════════════════════════════════════════
        print_header("TEST 4: Idempotency (same key × 50 retries)")
        for k in results:
            results[k] = 0

        show4 = await create_show(session, f"idemp-{uuid.uuid4().hex[:8]}", seat_names)
        show_id4 = show4["id"]
        idemp_key = str(uuid.uuid4())
        idemp_user = "user-idemp"

        tasks = []
        for _ in range(50):
            tasks.append(
                reserve(session, show_id4, idemp_user, ["B2"], idempotency_key=idemp_key)
            )

        responses = await asyncio.gather(*tasks)
        for status, body in responses:
            classify(status, body)

        print(f"  Confirmed (201): {results['confirmed']}  (expected: 1)")
        print(f"  Replays (200):   {results['idempotent_replay']}  (expected: 49)")
        print(f"  5xx:             {results['5xx']}  (expected: 0)")
        assert results["confirmed"] == 1
        assert results["idempotent_replay"] == 49
        assert results["5xx"] == 0
        print("  ✓ PASSED")

        # ══════════════════════════════════════════════════════════
        # TEST 5: Same key, different seats → 409
        # ══════════════════════════════════════════════════════════
        print_header("TEST 5: Same Key, Different Seats → 409")
        for k in results:
            results[k] = 0

        reuse_key = str(uuid.uuid4())
        reuse_user = "user-reuse"

        # First request with this key
        s, b = await reserve(
            session, show_id4, reuse_user, ["C1"], idempotency_key=reuse_key
        )
        classify(s, b)
        assert s == 201, f"First request should succeed, got {s}"

        # Same key, different seat → must be 409
        s2, b2 = await reserve(
            session, show_id4, reuse_user, ["C2"], idempotency_key=reuse_key
        )
        classify(s2, b2)
        assert s2 == 409, f"Expected 409 for key reuse with different seats, got {s2}"
        assert b2.get("error") == "idempotency_conflict"
        print("  ✓ PASSED")

        # ══════════════════════════════════════════════════════════
        # FINAL SUMMARY
        # ══════════════════════════════════════════════════════════
        print_header("FINAL SUMMARY")
        all_5xx = results["5xx"]
        print(f"  Total 5xx across all tests: {all_5xx}")
        if all_5xx == 0:
            print("  ✓ ALL TESTS PASSED — zero 5xx, all invariants hold")
        else:
            print("  ✗ FAILURES DETECTED")
        print()


if __name__ == "__main__":
    asyncio.run(main())
