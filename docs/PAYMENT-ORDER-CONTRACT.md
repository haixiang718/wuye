# Published bill payment orders

The legacy WeChat V2 single and arrears callback URLs now use the same durable posting path. Payment remains disabled by default. This change does not enable a merchant account, V3, real payment acceptance, refunds, or provider reconciliation.

`IFeePaymentOrderInnerServiceSMO` exposes internal `POST /paymentOrderApi/create`, `/query`, and `/confirm`. The merged business application already scans `com.java110.fee`; its shared MyBatis resources load `mapper/fee/PaymentOrderMapper.xml`.

`create` accepts a server-established `userId`, `communityId`, merchant `appId`/`mchId`, `orderType` (`SINGLE` or `ARREARS`), and `feeId` or `roomId`/`payObjType`. Optional `billId` and `oweId` select an exact snapshot. It ignores browser amounts and cycles. SQL requires an approved owner binding (`owner_app_user.state=12000`), the corresponding owner in the same community, a unique community merchant, a current published unpaid bill, and an unchanged fee start date. The response is the persisted order: `orderId`, `userId`, `communityId`, `appId`, `mchId`, `orderType`, integer fen `totalFee`, JSON-array `payload`, and `state=CREATED`.

Order creation commits before the provider request. `payment_order_item` permanently links each snapshot to its payment order. `payment_order_reservation` reserves each community/fee pair across billing periods. The same snapshot set, owner, merchant, and payment type reuse the pending order number. Overlapping scopes are rejected. A failed or abandoned provider request leaves `CREATED`; a repeat placement can use the same order number. Timeout, cache expiry, and a browser cancellation cannot release this reservation. Closing an abandoned order requires a future provider-confirmed close/reconciliation operation; this release workflow is outside the disabled-payment repair.

Pending-order reuse also compares every fee's frozen identifiers, owner/object, dates, labels, and amount; an unchanged combined total cannot hide changed fee allocations or dates. Before creating a JSAPI/App order, the front service queries all pages of the current user's approved bindings for the selected community and application type, rechecks every returned row, and requires one distinct OpenID. Duplicate bindings with the same OpenID are accepted; ambiguous or incomplete identity results are rejected before order creation.

`confirm` accepts `orderId`, `transactionId`, `appId`, `mchId`, `orderType`, and integer fen `totalFee`. It revalidates the stored ownership and amount. A local Spring transaction locks the order and published snapshot rows, reserves a globally unique provider transaction ID, inserts `pay_fee_detail` and receipt rows, advances the fee end date to the frozen deadline, settles `bill_owe_fee`, increases `bill.receipts`, releases active fee reservations, and commits `POSTED`. It preserves the bill date and frozen fee start date despite legacy `ON UPDATE CURRENT_TIMESTAMP` columns. Any error rolls back all posting writes, including transaction-ID reservation, so a later callback can retry. A committed duplicate must have the same transaction ID and returns `{code:0,state:'POSTED',orderId:...}` without another posting.

Callbacks require the URL `wId`, a valid V2 signature, application and merchant identity, the durable order, exact amount, and a successful `POSTED` business result. The selected merchant is scoped to the order's community. Missing Redis entries have no significance; unknown legacy orders return valid `FAIL` XML. Old pending orders can still post after a later bill is generated. A later snapshot whose start date became stale cannot collect money until the bill is reconciled and published again.

Apply `scripts/migrations/2026-11-02-payment-order.sql` through the supervised repair migration after backup. It creates three payment tables and conditionally adds the two receipt payer columns already referenced by the original 0.8 mappers. The migration runner must check columns before executing those `ALTER` statements. No credentials or OpenIDs belong in these tables.

Verification commands run serially because they share Java audit output:

```powershell
python scripts/simulation/run_payment_audit.py
python scripts/tests/run_payment_order_database_java.py
```

The first runs real callback and posting classes with process-local substitutes. The second copies table definitions (no rows) into a randomly named local audit schema, uses actual MyBatis and JDBC transactions with synthetic fixtures, and drops that exact schema in `finally`. Neither test makes provider, WeChat, Redis, SMS, or notification network requests.
