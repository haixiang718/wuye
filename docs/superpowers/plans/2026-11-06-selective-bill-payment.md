# Selective bill payment implementation plan

**Goal:** Owners choose one or more published bills to pay, with an accurate selected total and no implicit full-room charge.

**Architecture:** A shared selection helper drives checkboxes in the property list and arrears checkout. Existing payment placement forwards selected owe IDs. The fee service's locked snapshot query and exact-set validation enforce selection before creating a durable order. No schema migration.

**Tech stack:** Vue2/uni-app H5, Spring Java8, MyBatis/MySQL, Node tests and JUnit.

- [x] Add failing payment-database tests for subset placement/posting, exact selection, stale/foreign/malformed IDs and pending-order selection changes. Add H5 tests for empty/default selection, totals, navigation and submitted IDs.
- [x] Extend PublishedBillToPaySMO, FeePaymentOrderInnerServiceSMOImpl and PaymentOrderMapper.xml to require and enforce selected owe IDs for ARREARS. Preserve SINGLE and existing payment recovery.
- [x] Add utils/bill-selection.js. Modify roomFeeListNew and roomOweFee with checkboxes, select-all, selected total, no-selection protection and checkout revalidation. Reset selection when room/data changes; prevent toggling during submission.
- [x] Run H5 tests, payment SQL/transaction tests and V3 placement/recovery audit. Compile backend and H5. Verify compiled checkboxes and amount/navigation behavior at phone widths without calling a payment provider.
- [x] Review, back up and deploy business/front/H5 artifacts. Verify 21 runtime checks and latest public build. Commit only this task's files and record verification limits.

Verified on 2026-11-06:

- H5: `npm test` — 128 tests passed, including parking arrears caller compatibility and slash-formatted checkout dates.
- Payment database: `python scripts/tests/run_payment_order_database_java.py` — 30 tests passed using disposable schemas. Subset posting leaves the other snapshot unpaid.
- V3 orchestration: `python scripts/tests/run_wechat_pay_v3_flow.py` — 101 offline checks passed.
- Backend reactor built successfully; business/front JARs replaced with backups retained under the private selective-payment build directory. Other services stayed running.
- Compiled H5 browser: `node scripts/owner-h5/selection-smoke.cjs` — 360/390/430 px passed. Verified default empty selection, visible checkmarks, exact dates, selected totals, navigation, stale-selection rejection and only selected IDs in submission. All API responses synthetic; no payment provider called.
- Runtime: 21/21 readiness checks. Public authenticated H5 index matched the compiled build. Deployed internal API rejected empty selection before database access.
- Independent review found a shared parking caller missing `oweIds`; both callers now explicitly send all valid displayed bill IDs, rejecting incomplete data. Incremental review passed.

Limits: no new real payment or notification was initiated. Existing pilot payment eligibility and 0.01-yuan collection policy remain in force. The H5 compiler retains its existing two bundle-size advisories.
