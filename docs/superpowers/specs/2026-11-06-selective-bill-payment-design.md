# Selective owner bill payment

The user requested checkboxes so owners can pay selected property fee items instead of all arrears. Implement this authorized behavior in the existing list and checkout flow.

- Both the property fee list and room arrears checkout expose per-bill checkboxes, select-all, selected count and selected total. Default selection is empty. A bill remains indivisible: selection chooses complete published bill snapshots, not an editable partial amount.
- Preserve single-bill details. The list's selected-payment button navigates to checkout with the selected owe IDs. Checkout requeries current published unpaid snapshots, rejects stale selections and lets the owner adjust the selection before submitting.
- Every ARREARS payment requires 1–100 unique owe IDs. Public front preserves that array; business independently validates it, queries only authorized snapshots for the room/community/user, and requires the exact selected set to match. Missing, paid, unpublished, foreign and duplicate IDs reject the whole request. Never fall back to all room bills.
- Prices and accounting remain server-authoritative. Successful posting settles and receipts only the frozen selected snapshots; unselected bills remain unpaid. Existing callback idempotency, pending-order recovery, pilot eligibility and collection cap stay enforced.
- No real payments, notifications or new live business fixtures are required to verify this change. Use disposable database fixtures, unit tests and mocked compiled-browser checks.
- The reachable parking arrears caller also uses this shared API. Preserve its existing full displayed-bill payment behavior by explicitly passing all eligible displayed owe IDs; reject missing or incomplete snapshots rather than silently falling back. The disabled contract flow stays disabled.
