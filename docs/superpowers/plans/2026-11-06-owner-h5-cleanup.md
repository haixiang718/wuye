# Owner H5 cleanup implementation plan

Execute the user's requested scope in this session, sequentially using systematic debugging and regression tests.

**Goal:** A focused owner H5 with visible bill amounts and accurate authorized payment history.

**Architecture:** Owner navigation policy and Vue pages; front resident response on existing history route; dedicated MyBatis SQL in fee service using authenticated user and approved relationships. No schema migration.

- [x] Add failing owner history authorization and H5 tests reproducing missing feeId history and absent list amounts.
- [x] Implement dedicated internal history query and authenticated resident response, with count/pagination and actual paid details.
- [x] Remove requested owner pages/menus; update existing preservation expectations.
- [x] Repair history loading, pagination and error handling; add list money and bill interval.
- [x] Run focused and existing tests, production SQL checks and build backend/H5.
- [x] Deploy verified artifacts, verify local/public requests and mobile rendering; document exact verification limits.

## Verification and deployment

- Front authorization regression: 49 tests passed. The initial no-fee-ID history tests failed with 403 before the fix.
- Production history mapper and repository: 8 tests passed against cloned schemas in disposable databases. Covered completed fees without legacy attributes, multiple properties, family vehicles, foreign/pending/deleted bindings, refund exclusions, duplicates and pagination. TT data was not mutated.
- Owner H5: 120 tests passed, including loading/error/empty/retry/session changes and existing payment recovery tests.
- Backend Maven reactor build succeeded. Deployed only business and front JARs, with previous JARs retained in the private build rollback directory. H5 production build succeeded; only existing bundle size advisories remain.
- Compiled browser checks passed at 360/390/430px: removed entries absent, amounts visible with long fee names, payment time/property displayed, no horizontal overflow, empty/error retry states. Browser API and identity were synthetic; these checks do not assert real mobile OAuth.
- Read-only deployed internal history query for the accepted pilot account returned the real 0.01 payment exactly once, with property and payment time. No payment or notification was triggered.
- Runtime health: 21/21. Both public H5 and management entry returned HTTP 200. Private evidence is under `.runtime/owner-h5-cleanup-check`; real owner IDs and tokens are excluded from this document.
- Access the updated H5 at `https://h5.jiaanfuwu.cn/?v=h5-cleanup-20261106`. Business services continue to depend on the running local workstation and existing public reverse tunnel.
