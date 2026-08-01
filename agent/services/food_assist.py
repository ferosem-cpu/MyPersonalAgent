"""Food & grocery ordering assist (PLAN_V2 Task 6.3) - honest scope.

Reality check: none of Swiggy/Zomato/Blinkit/Zepto/BigBasket expose a public
consumer ordering API, and automating checkout would touch payments (which must
always stay with the human) and break constantly against ToS. So v1 here is
**cart assist**, not checkout automation:

1. A shared shopping list, stored as tagged notes through the existing
   storage.py memory collection (no schema change - syncs to the phone for free
   via the sync already built in Phase 2).
2. Deep-link launchers that open the right site/app with the query/list ready,
   rather than trying to add items into a real cart programmatically.

Playwright semi-automation that fills a real cart and stops at checkout is
explicitly NOT built here - deliberately deferred, see handover.md. Building it
now would be scope creep past what this task asked for and needs separate
approval given it touches a real account's cart.
"""

from __future__ import annotations

import urllib.parse
from typing import Any

SHOPPING_TAG = "shopping"

FOOD_APPS = {
    "swiggy": "https://www.swiggy.com/search?query={query}",
    "zomato": "https://www.zomato.com/search?q={query}",
}
GROCERY_APPS = {
    "blinkit": "https://blinkit.com/s/?q={query}",
    "bigbasket": "https://www.bigbasket.com/ps/?q={query}",
    "zepto": "https://www.zeptonow.com/search?query={query}",
}


def add_to_shopping_list(storage, item: str, qty: str = "") -> dict[str, Any]:
    text = f"{qty} {item}".strip() if qty else item
    return storage.remember(text, tags=[SHOPPING_TAG])


def show_shopping_list(storage) -> list[dict[str, Any]]:
    return [n for n in storage.list_items("notes") if SHOPPING_TAG in (n.get("tags") or [])]


def clear_shopping_list(storage) -> int:
    items = show_shopping_list(storage)
    for note in items:
        storage.soft_delete_item("notes", note["id"])
    return len(items)


def order_food(query: str, app: str = "swiggy") -> dict[str, Any]:
    app = app.lower()
    if app not in FOOD_APPS:
        raise RuntimeError(f"Unknown food app '{app}'. Choose one of: {', '.join(FOOD_APPS)}")
    url = FOOD_APPS[app].format(query=urllib.parse.quote(query))
    return {"app": app, "url": url}


def order_groceries(storage, app: str = "blinkit") -> dict[str, Any]:
    app = app.lower()
    if app not in GROCERY_APPS:
        raise RuntimeError(f"Unknown grocery app '{app}'. Choose one of: {', '.join(GROCERY_APPS)}")
    items = show_shopping_list(storage)
    if not items:
        raise RuntimeError("Shopping list is empty - add items first with add_to_shopping_list.")
    query = ", ".join(n["text"] for n in items)
    url = GROCERY_APPS[app].format(query=urllib.parse.quote(query))
    return {"app": app, "url": url, "items": [n["text"] for n in items]}
