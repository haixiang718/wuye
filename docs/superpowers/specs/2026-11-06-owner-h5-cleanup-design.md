# Owner H5 simplification and billing visibility

The user explicitly requested these changes: remove family members, rental, customer contact, parking applications, vacant-room applications, renovation, contract fees and questionnaires from the home page; remove renovation from My; repair payment history; display payable amounts without opening bill details.

## Design

- Remove the requested owner navigation entries and their page registrations; keep a route guard for old links. Scope this to the owner application, preserving management and staff capabilities. Keep property fees, parking fees, temporary parking and existing My entries that were not requested for removal.
- Reuse `/app/fee.queryFeeDetail` for history. Its current resident guard requires feeId while the My page sends ownerId, causing rejection. Its legacy owner-attribute filter also misses payments without that attribute. Add a resident history response using a dedicated internal query of actual payment details, scoped from authenticated user and approved current property/vehicle relationships. Never accept browser owner IDs as authorization. Preserve the staff legacy endpoint.
- Return normal completed payment records (state 1400), ordered by payment time and ID, with bounded pagination, fee/room name, money and billing interval. Include completed one-time fees and both online and manual collection. Do not create history from unpaid bill snapshots. No new database tables or mutations.
- History refreshes on entry and owner/session change, supports load-more, distinguishes empty/error/loading, handles missing dates, and never leaves previous-owner records after identity changes.
- Property bill rows display server-frozen amount to two decimals, plus billing interval. Missing/invalid amounts show an explicit unavailable label. Payment still reloads and authorizes details server-side.

## Verification

Failing regression tests first for authorization/history, SQL ownership/pagination, H5 loading/errors and money display. Run existing owner and H5 suites, compile backend, build H5, check mobile layouts and public resources. Read-only verification must find the accepted 0.01 payment and reject another user's scope; no payment, notification or new business record is generated. Preserve existing unrelated changes and retain runtime rollback artifacts.
