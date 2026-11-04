# Review and Notify Implementation Plan

> **For agentic workers:** Use subagent-driven-development for the independent backend and UI tasks, then spec/security review. User authorized implementation; do not request execution approval again.

**Goal:** One staff action publishes the H5 bill and queues its official-account payment notification with progress and safe retry.

**Architecture:** Reuse BillReviewService and BillNotificationService; add a Redis-backed, resumable task processor in service-api, an admin-only status route, and current-page status polling in billManage. Never send real messages during acceptance.

**Tech Stack:** Java 8/Spring Boot, Redis/Jedis, existing Feign/MyBatis billing services, Vue/vc, JUnit and Node tests.

- [x] Backend TDD: new publication workflow/task store and processor in `service-api/.../bmo/fee/impl`, update ReviewBillListener and add status listener, augment BillNotificationService with first-notification semantics under its existing lock. Test boundaries via in-memory queue and stub notifications; no DB, network, worker startup or real sends. Add Java runner under scripts/tests. Contract and edge cases are specified in the approved design.
- [x] UI TDD: change billManage review confirmation/body to notify:true, show QUEUED/RUNNING/COMPLETED/FAILED results, poll only visible active tasks, stop on destroy, offer retryNotifications:true only for retryable failures. Keep generation/filter/pagination behavior. `npm test` must pass including new publication tests.
- [x] Integration: add admin-only N/Q GET route SQL and local-routes entry for fee.billNotificationStatus, update precise source tests, complete spec/security review, run backend and UI suites sequentially where they share compilation output.
- [x] Runtime: back up API JAR and Redis/database before persistent work, build service-api with dependencies, install precise route and refresh cache, restart API, verify health and formal H5 entry. Use a fixture with unbound synthetic owner for real UI publish→queue→actionable no-binding failure→retry, and deterministic fake-WeChat tests for accepted/restart behavior. Do not modify/re-send the user's previously notified bill. Clean fixture and task keys by verified IDs; compare business snapshots. Document evidence and commit only this feature.

Commands: `python scripts/tests/run_bill_publication_java.py`, `python scripts/tests/run_bill_notify_java.py`, new task-workflow runner, `npm test` in the admin frontend, `python scripts/runtime_ops.py health`, plus scoped real-browser acceptance. Maven build and Java test compilers must run sequentially. Database/fixture changes must remain sequential and cleanup must run even after failure.
