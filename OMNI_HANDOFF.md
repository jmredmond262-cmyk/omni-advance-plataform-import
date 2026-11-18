# OMNI — technical handoff for GitHub Copilot

**Snapshot date:** 2026-11-17  
**Repository:** https://github.com/jmredmond262-cmyk/omni-advance-plataform-import  
**Handoff branch:** `copilot/omni-handoff-2026-11-17`  
**Starting code branch:** `feat/demo-merchants-worker-spark`  
**Related open draft PR:** [#16](https://github.com/jmredmond262-cmyk/omni-advance-plataform-import/pull/16)

## Purpose

Continue the existing OMNI project from its current code state. This is a handoff, not a rewrite or a greenfield project. Preserve the existing architecture, screens, business rules, data contracts, and working functionality unless a verified defect requires a targeted change.

## Current Git starting point

The handoff branch was created from the head of `feat/demo-merchants-worker-spark`, commit:

`ed8a944e17f101d6225bd3ba018f846e18337718`

That source branch has an open draft PR #16 against `fix/demo-orders-traceability-merchant-claim`. Do not assume PR #16 is merged. Review its commits and diff before deciding whether to merge, rebase, close, or continue it. Do not merge automatically.

PR #16 describes the DEMO merchant Worker work: `provision`, `claim`, `saveCatalog`, and the added `createOrder` route/client integration. Its description says no Worker deployment, Firebase mutation, or rules publication was performed. Treat deployment as not authorized.

## Project intent and operating constraints

OMNI is an Android superapp with (among other modules) wallet/payments, nearby businesses, demo commerce and ordering, QR workflows, contacts, work/community features, events, weather/daily hub, and a separate Admin app for operational visibility and analytics.

Non-negotiable constraints:
- Preserve existing project architecture; do not create a replacement app or redesign unrelated screens.
- Free/demo/testing services only for now. No paid credentials, real-money payment processing, or production financial flows.
- Demo merchants must remain separate from REAL merchants and real commerce data.
- Do not deploy Cloudflare Workers, publish Firebase rules, alter live Firebase data, or perform destructive operations without explicit owner authorization.
- Do not expose secrets, signing keys, tokens, service-account JSON, or private configuration in commits, logs, or reports.
- Keep OMNI Principal and OMNI Admin as distinct applications.
- User does not use Android Studio. Prefer GitHub Actions artifacts/workflows and clear browser-based steps.
- Preserve original app signing identity for update-install. Temporary CI signing APKs are not valid substitutes for updating an installed production-signed app.
- The user expects complete, installable APKs rather than unverified patches. Never say an APK is ready until an actual successful workflow artifact has been checked.

## Known app identifiers and services

- Firebase project: `omni-57f69`
- Principal Android application ID: `com.aistudio.omnihub.qtzmvd`
- Admin Android application ID: `com.omni.adminpanel`
- Cloudflare Worker endpoint previously used: `https://omni-admin-api.jmredmond262.workers.dev`
- Legacy Firebase Functions region: `us-central1`
- Expected original signing certificate SHA-256 recorded in the build workflow:
  `486513DCA524F8C09134DB97A7D5650A2F2461E4306DB181B7C2DFE2D40E495B`

The signing fingerprint is a verification reference, not a signing secret. Never attempt to reconstruct or expose a keystore.

## DEMO commerce: preserve existing implementation

The following Firebase callable functions already exist in `functions/src/index.ts` and must not be described as missing or rebuilt merely because some client calls still use Firebase callable transport:

- `createDemoStoreOrder`
- `simulateDemoCardPayment`
- `payStoreOrderDemo`
- `updateDemoStoreOrderStatus`
- `confirmDemoCashCollection`
- `refundDemoStoreOrder`

The Principal UI has existing flows for card simulation, order status updates, customer cancellation, receipt handling, and cash collection in `OrderCenterScreen.kt`. The exact current call sites and behavior must be re-read from the current branch before editing.

`DemoWalletRepository.kt` already contains `payStoreOrderDemo(orderId, merchantUid, amount, done)` and wallet transaction logic. Treat wallet/payment logic as high risk: do not replace atomic transactions with simplistic Firestore writes.

The Worker client `DemoMerchantApi.kt` has methods including:
- `provision()`
- `claim(code)`
- `saveCatalog(payload)`
- `createOrder(payload)`

Recent changes on the starting branch:
- `OrderCenterScreen.kt` provisioning uses `DemoMerchantApi.provision()`.
- `DemoMerchantApi.kt` adds `createOrder(payload)`.
- `workers/src/index.js` adds the Worker-side `createDemoStoreOrder` route.
- `OrderCenterScreen.kt` submits order creation through `DemoMerchantApi.createOrder(...)`.

These changes are part of the starting point. Inspect actual code and compare Worker request/response semantics with the existing callable before making further changes. Do not infer parity from the existence of a route.

Potential remaining integration question to verify (not a confirmed missing feature): some Principal call sites may still invoke Firebase callables for `simulateDemoCardPayment`, `updateDemoStoreOrderStatus`, `refundDemoStoreOrder`, `confirmDemoCashCollection`, and merchant claim. Determine whether these are intentionally retained or must be migrated for the chosen free-tier architecture. If migrating, preserve authorization, validation, atomicity, idempotency, status history, inventory, cashback, and Admin traceability.

## Admin requirements and UX constraints

Admin is a separate, primarily read-only operations/analytics panel. Do not add general data-entry controls. Account/password issue handling may be considered only when explicitly requested.

Visual direction: dark cyber-neon / mask identity, professional control-center dashboard, compact cards and buttons, distinct neon accents. Avoid oversized generic cards and generic placeholders.

Traceability must be contextual and specific. Avoid labels such as "Registro Omni", "Sin categoría", "Otros", or "clasificación pendiente" when the underlying event/module can be identified. In account/balance views, show the account holder as the primary name and alias as secondary.

Admin real-time map requirements: use the location source already used by Principal weather/device location; keep the map in Admin's "Mapa en tiempo real" section. The desired design is a custom cyber-neon treatment, not a plain default OpenStreetMap presentation. Previous work reported MapLibre style/canvas initialization but also a blank canvas issue (including a prior 364x0 canvas dimension). Reproduce and diagnose with actual device/browser logs before changing map architecture.

## Build and CI facts to re-check

The known `.github/workflows/android-build.yml` previously:
- did not include `feat/demo-merchants-worker-spark` in its push branch filters;
- had a `workflow_dispatch` trigger;
- expected secrets including `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`, `OMNI_ADMIN_GOOGLE_SERVICES_JSON_BASE64`, `OMNI_ADMIN_AUTH_PROJECT_ID`, and `OMNI_DEBUG_KEYSTORE_BASE64`;
- checked the original signing fingerprint above;
- expected Principal version `1.4.5` and Admin version `1.4.9` at the time it was inspected.

These are historical observations. Re-open the workflow and current Gradle/version files before relying on them. Do not change secrets or signing configuration. If the original signing material is unavailable to the workflow, report the exact blocker; do not produce a fake "update-ready" APK.

A previous CI run was cancelled, not passed. A cancelled run is not evidence of successful compilation or tests.

## Required working method

For each task:
1. Read the relevant current files and call sites first.
2. Search for existing implementations across the whole repository before creating new ones.
3. Write a short inventory: already implemented, integrated, not integrated, verified, unverified, actual blocker.
4. Make the smallest targeted change that solves a demonstrated issue.
5. Add/update tests where feasible.
6. Run relevant validation and inspect full logs, not just workflow status.
7. Report exact commit/PR, files changed, test evidence, and remaining uncertainty.
8. Never claim runtime verification from static code inspection alone.

## Immediate first action

Perform a read-only audit of this handoff branch and the source PR #16:
- inspect repository structure and Gradle modules;
- inspect Principal/Admin build workflows and signing requirements;
- inspect the DEMO merchant Worker routes and client call sites;
- inspect the existing callable payment/cancellation/refund functions and wallet repository;
- compare PR #16 to its base and identify any open integration gaps;
- produce an evidence-backed status report.

Do not edit application code, merge PRs, deploy services, change Firebase rules, or write production data during this first audit.

## Infrastructure documentation

Before any later work involving infrastructure, Android signing, Firebase, or external APIs, consult the security and integration references in [`docs/infraestructura/credenciales/INDICE.md`](docs/infraestructura/credenciales/INDICE.md):

- [Firebase configuration and safeguards](docs/infraestructura/credenciales/firebase.md)
- [Android signing identity and workflows](docs/infraestructura/credenciales/android-signing.md)
- [External APIs and integrations](docs/infraestructura/credenciales/apis.md)

These references contain no credential values. Treat their inventory gaps as unverified; inspect only non-sensitive configuration and never add secrets to the repository.
