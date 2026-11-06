# V2 Module Pruning Implementation Plan

> **For agentic workers:** Use executing-plans for sequential integration and dispatching-parallel-agents for the independent frontend and database-tool tasks. User approved the design on 2026-11-06; execute without another approval gate.

**Goal:** Remove the agreed unused modules and dedicated tables while preserving verified property, billing, WeChat, payment and parking behavior.

**Architecture:** Keep the current merged Java service layout. Remove module code by explicit source inventory and dependency closure, retain the parking protocol subset, and migrate only a reviewed database allowlist after clean builds, business regression and isolated restore rehearsal. V1 tag `v1.0.0` and private full backups remain available.

**Tech Stack:** Java 8 / Spring Boot / Maven / MyBatis, Express, uni-app Vue 2 H5, MySQL 8, Python maintenance tools.

## Task 1: Frontend retirement

**Files:** `wuye-f/MicroCommunityWeb-v0.8/`, `wuye-xcx2/WechatOwnerService-v0.8/`, `wuye-xcx/PropertyApp-v0.8/`, relevant Node tests, and `docs/validation/2026-11-06-v2-frontend.json`.

- [x] Add failing HTTP/navigation regression cases for mall, renting and secondhand paths absent from the V1 boundary. Preserve parking dot/slash callbacks and owner-photo requests.
- [x] Enumerate explicit module-only pages, components, JS helpers and images; check retained templates/imports before deleting. Remove agreed modules physically, not just menu entries. Keep generic widgets and unrelated previously hidden modules outside this scope.
- [x] Update page registration and feature policies, retaining 410 responses for stale links. Do not remove room records, shop-type rooms, contracts or property-company stores by substring.
- [x] Run both frontends' `npm test`, staff-app tests if changed, and owner `npm run build:h5`. Record removed paths and counts.

## Task 2: Safe database retirement tool

**Files:** create `scripts/migrate_v2_pruning.py`, `scripts/v2-retired-tables.json`, `scripts/tests/test_v2_pruning.py`; reuse `scripts/runtime_ops.py` backup, metadata and restricted clone helpers. Record rehearsal in `docs/validation/2026-11-06-v2-database.json`.

- [x] Write failing isolated MySQL tests for dry-run preservation, non-allowlisted source rejection, backup failure, foreign/shared dependencies, exact config-row selection, data-preserving rollback and repeat application.
- [x] Derive candidates from `2026-11-06-v2-pruning-preflight.json`; omit at least the six parking-shared machine/attrs/translate tables. Root will supply additional shared exceptions found while cleaning Java.
- [x] Default to plan only. Require full verified backups before actual source DDL, refuse pending payment orders and active writes, verify explicit table allowlist and dependency objects; never accept arbitrary user-provided schema/table names for source deletion.
- [x] Rehearse forward and inverse operations in an exclusively created random `ops_restore_*` schema, using existing validated source backups. Keep a progress journal and rollback only known committed steps. Report actual configuration changes separately from preserved business rows.
- [x] Do not apply DDL to `TT` or `hc_community` until root has integrated code, reviewed final allowlist and passed all required regression.

## Task 3: Java dependency closure and retirement

**Files:** `wuye-b/MicroCommunity-v0.8/{java110-bean,java110-interface,java110-utils,java110-core,java110-service,java110-db,service-api,service-front,service-user,service-community,service-common,service-report,service-job,service-goods,pom.xml}`, `scripts/local.py`, release scripts/tests.

- [x] Enumerate module-only Java packages/classes and mapper XML; build class/import reference graph before removal, including fully qualified strings and dynamic listener wiring.
- [x] Write/extend boundary regressions: old module calls rejected, property/bill/payment/WeChat calls retained, parking protocol plus internal machine dependencies retained.
- [x] Detach unused door/repair dependencies from retained parking listeners, owner photos and shared tasks. Remove only exclusive classes; retain shared DTOs/utility contracts when removing them would break supported consumers.
- [x] Remove `service-goods` from reactor, launcher and release output. Remove dedicated mall/group-buy jobs. Keep payment recovery and fee/notification job definitions and current enablement policy.
- [x] Delete exclusive module source and SQL mappers using the reviewed inventory. `mvn clean package` supported reactor; repair meaningful missing dependencies at their callers rather than adding inert service stubs.
- [x] Record exact removed source paths and any extra shared-table exceptions for Task 2.

## Task 4: Regression with code integrated

- [x] Run Java boundary/owner creation/OAuth/access tests, payment database (40 baseline), review database (10 baseline), owner history (8 baseline), account/contract and publication/notification tests. Shared `.runtime/billing-audit` runners run serially.
- [x] Build Java cleanly and H5; verify no retired controllers/listeners/tasks/mappers remain in output. Do not use stale target classes as proof.
- [x] Verify HTTP direct and proxy retirement responses and preserved parking endpoints with synthetic inputs, plus UI and public ingress without real notifications or payment.

## Task 5: Actual database migration and runtime

- [x] Review clone/restore evidence and final table allowlist against Java artifacts and live dynamic SQL/config. Keep framework/shared tables.
- [x] In launcher lock stop business writers and owner/admin entrypoints, confirm no pending CREATED payment, take latest backups and capture retained business checksums. Preserve tunnel and infrastructure.
- [x] Apply reviewed migration to dedicated local `TT` and `hc_community`; compare retained business data checksums and config changes against exact plan.
- [x] On failure stop, inspect committed DDL and use observed-step rollback; do not overwrite post-migration payment data with a stale whole-db snapshot.
- [x] Restart merged services, owner H5 and gateway; verify all readiness and retained read-only flows and public domains. Do not trigger real payment or notification as a side effect.

## Task 6: Review, release records and Git

- [x] Review the final diff, delete-only manifest, migrator failure behavior and payment/parking exceptions.
- [x] Update approved design status, database function map and V2 release guide with actual counts, validations and remaining limitations. Report measured memory/package differences only where measured under comparable conditions.
- [x] `git diff --check`, targeted tests after any review fix, commit to `codex/v2-module-pruning` and push. Keep V1 tag/branch unchanged. Implementation commit: `99cbdf1`.
