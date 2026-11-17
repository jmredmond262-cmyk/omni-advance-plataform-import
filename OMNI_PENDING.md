# OMNI — handoff verification queue

**Snapshot:** 2026-11-17  
**Source branch:** `feat/demo-merchants-worker-spark`  
**Source commit:** `ed8a944e17f101d6225bd3ba018f846e18337718`  
**Related PR:** [#16 — open draft](https://github.com/jmredmond262-cmyk/omni-advance-plataform-import/pull/16)

This file intentionally separates known existing code from work that still needs evidence. It is not permission to rewrite or deploy anything.

## Already present in source (do not recreate)

- Firebase callable implementations in `functions/src/index.ts`:
  - `createDemoStoreOrder`
  - `simulateDemoCardPayment`
  - `payStoreOrderDemo`
  - `updateDemoStoreOrderStatus`
  - `confirmDemoCashCollection`
  - `refundDemoStoreOrder`
- `DemoWalletRepository.kt` has a `payStoreOrderDemo(...)` flow and wallet transaction logic.
- `OrderCenterScreen.kt` already contains client flows for payment simulation, status transitions, customer cancellation, receipt handling, and cash collection.
- Worker API client methods include `provision`, `claim`, `saveCatalog`, and `createOrder`.
- The current source branch includes Worker-backed merchant provisioning and order creation integration.

These are known from prior repository inspection. Copilot must re-open the current branch and verify exact line numbers and current behavior before editing.

## Open verification items (not automatically missing features)

1. **PR #16 state and contents**
   - It is an open draft PR, not merged.
   - Review its complete diff and CI checks against its base.
   - Decide with the owner before merging.

2. **Worker / callable parity**
   - Compare Worker `createOrder` behavior and response fields with the existing Firebase callable `createDemoStoreOrder`.
   - Verify validation, totals, delivery data, stock behavior, QR/nonce fields, status history, and Admin-consumed fields.
   - Do not assume parity merely because both create orders.

3. **Remaining client transport**
   - Inspect whether card simulation, payment, order status, cancellation, refunds, cash collection, and merchant claim still call Firebase callable endpoints.
   - Determine whether this is intentional or a Spark/free-tier migration requirement.
   - Existing implementations are not to be rebuilt. Any migration must preserve their full behavior and security.

4. **Worker validation and deployment**
   - Run the repository's available Worker tests/type/syntax checks.
   - Verify route authentication and error handling.
   - No deployment has been authorized or reported as completed.

5. **Principal and Admin build**
   - Re-check current workflow triggers, Gradle configuration, package IDs, version codes, secrets references, and signing checks.
   - Trigger builds only through authorized existing workflow mechanisms.
   - Verify both APK artifacts and their signatures. Temporary signing does not satisfy update-install compatibility.

6. **End-to-end DEMO test**
   - Use a safe test account and DEMO-only merchants after the owner authorizes runtime testing.
   - Verify merchant provisioning/claim/catalog, multi-item order, pickup/delivery, payment simulation, QR, status history, cancellation/refund, and Admin traceability.
   - Record evidence for each state transition; do not infer from code.

7. **Admin data and map**
   - Verify contextual transaction labels and titular/alias presentation.
   - Reproduce the map's blank-canvas issue using current device logs and dimensions before selecting a fix.
   - Keep Admin read-only except explicitly approved account-management functions.

## Current known CI caveat

A previously inspected `.github/workflows/android-build.yml` did not automatically trigger for `feat/demo-merchants-worker-spark` pushes, although it had a manual dispatch trigger. A previous workflow run was cancelled. Re-inspect the current workflow and run history; do not report the Android build as passed based on that cancelled run.

## Definition of done

A task is complete only when:
- the relevant implementation and integration are verified in the current branch;
- applicable automated tests/builds pass;
- runtime behavior is tested when the task requires runtime behavior;
- the resulting commit/PR and evidence are recorded;
- no unauthorized deployment, live-data change, or rules change occurred.
