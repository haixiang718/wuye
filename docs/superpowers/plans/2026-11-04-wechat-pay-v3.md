# WeChat Pay V3 Implementation Plan

> Execute the approved four-stage payment scope in the current session. Use test-driven-development and requesting-code-review. Independent provider, order lifecycle and H5 tasks may be delegated under subagent-driven-development.

**Goal:** Approved H5 bills can be paid with V3 JSAPI and reliably posted once, with authenticated recovery and a controlled real-payment acceptance step.

**Architecture:** Official SDK provider in front-service calls existing fee-side durable orders; signed callback and provider queries share atomic posting. Existing gateway exposes only exact allowed paths. Private configuration remains outside Git.

**Tech Stack:** Java 8, Spring Boot 2, official wechatpay-java 0.2.17, MyBatis/MySQL, uni-app H5, Node gateway, nginx.

## Tasks

- [x] Provider: add SDK dependency, private settings loader, signed prepay/query/close and notification parser. Synthetic RSA/AES fixtures must pass, tampered/old callbacks fail.
- [x] Orders: add immutable V2/V3 marker, scoped bounded pending query and transactional confirmed close. Preserve receipt/ledger invariants and closed order history. Run isolated real-SQL lifecycle tests.
- [x] Orchestration: use project-unique merchant and approved OpenID; create trusted V3 orders before prepay; callback and reconciliation validate merchant/AppID/order/amount/currency/trade type before confirm. Test retry/idempotence and owner access using synthetic providers.
- [x] Controller and recovery: exact public notify endpoint, authenticated query/close, scheduled bounded recovery; callback errors retry; redact errors and never report provider success as posting success.
- [x] H5: native bridge RSA invocation, authoritative status polling, cancel recovery and user-scoped pending recovery. Run existing H5 snapshot and nonpayment regression tests.
- [x] Ingress: allow exact payment callback independent of access gate; total switch for new payment routes, preserve raw body/headers, deny other paths. Add gateway tests; update nginx source and verify generated config.
- [x] Runtime: prepare private notify/pilot settings, update launcher configuration without writing secrets to commands, stage build and test. Deploy only verified builds and retain rollback.
- [x] Review: independent code review; fix material findings; run targeted payment, SQL, H5 and gateway suites plus project build.
- [ ] Acceptance: create or identify an isolated 0.01 test bill with explicit owner identity, let user perform real phone payment, verify merchant result and local accounting. Until this step is completed report real payment as unverified.

Never include existing unrelated dirty files in the payment commit. Do not enable universal owner collection automatically.

## Phone acceptance follow-up (2026-11-04)

- The first phone attempt opened the cashier but failed; the exact initial error was unavailable. No payment or posting has been established.
- Actual signed NOTPAY responses can omit trade_type. Actual signed CLOSED responses can also omit amount. Both protocol cases now have regression coverage; SUCCESS retains mandatory trade type, currency, amount and transaction validation.
- Latest offline regression: SDK 18 tests, orchestration 87 checks, H5 116 tests. CLOSED relaxation independently reviewed.
- H5 now retains the native failure message after an authenticated unpaid query. Await phone retry and verify the resulting provider and accounting evidence before checking Acceptance.
