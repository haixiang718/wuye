# Independent CNY 0.01 acceptance bill

`scripts/prepare_wechat_pay_v3_pilot.py` prepares a manually authored, isolated payment fixture for one explicitly selected real owner and one selected existing room. The owner's name and property identifiers stay in private runtime records. It does **not** exercise normal fee generation, bill generation, review, or publishing. The published state is part of this acceptance fixture.

Run read-only validation first:

```powershell
python scripts/prepare_wechat_pay_v3_pilot.py --plan
```

After reviewing the selected scope, prepare the bill explicitly:

```powershell
python scripts/prepare_wechat_pay_v3_pilot.py --apply
```

The private selection is `.runtime/wechat-pay-pilot-selection.json` and requires `approvedForPaymentPilot: true` plus a nonempty `requestedOwnerName` following explicit session authorization. The helper crosschecks that private name against the exact current selected owner/member and rechecks the approved WECHAT binding, community, active room relation, and existing fee accounting source. Other owners may be bound to the same user only when aggregate counts establish exactly one distinct nonempty OpenID across all approved WECHAT bindings in the community; the helper never retrieves identity values. Provenance records `sameIdentityMultiBindingSupported: true`. The pilot itself still has exactly one selected owner/member and one selected room. Collection must remain disabled. An omitted effective front payment flag uses the application's disabled default. The private merchant profile is inspected only for its `enabled` boolean; no merchant values are logged. No provider or notification API is called.

Apply creates a new `PV3` plus twelve hexadecimal character prefix and distinct config, fee, bill, and owe identifiers shorter than 30 characters. Exactly four new rows are inserted: a fixed CNY 0.01 one-time configuration (`005`, `2006012`, `2002`), a fee on the selected existing room, a current `PUBLISHED` bill with zero receipts, and an unpaid (`1000`) owner snapshot. The billing interval is the current local calendar month. Owner telephone is deliberately empty; no phone, identity card, OpenID, or merchant credential is recorded in provenance.

Apply obtains the existing local migration lock, hashes existing identity, room, fee, bill, receipt, payment, account, and contract rows while excluding only the four new fixture IDs, and calls the existing full TT backup helper. It atomically saves `.runtime/wechat-pay-v3-pilot.json` with selected IDs, expected fixture values, backup metadata, and preservation hashes before database writes. Four strict, explicit-column inserts and SQL verification guards run in one InnoDB transaction; a SQL failure closes the client connection and rolls back uncommitted inserts. The helper verifies the four rows and preservation hashes afterward. Stop other business writers during this operation so concurrent legitimate updates do not cause the preservation check to fail.

Rerunning verifies the recorded rows and never overwrites them. A missing/changed row or paid fixture causes refusal for inspection; no payment, receipt, or ledger history is removed. A failed transaction can be retried only when all four recorded IDs remain absent and the saved status is `planned`.

Offline guard tests use temporary files and mocked database access:

```powershell
python -m unittest discover -s scripts/tests -p test_prepare_wechat_pay_v3_pilot.py
```

Preparing this fixture does not create a payment order or request WeChat prepay, enable general collection, validate notifications, or establish successful payment acceptance. Those steps require their own controlled verification.
