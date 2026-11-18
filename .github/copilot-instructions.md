# OMNI repository instructions for GitHub Copilot

## Preserve, inspect, verify

- This is an established multi-module Android project. Extend the current implementation; do not scaffold a replacement or rewrite architecture without explicit approval.
- Before suggesting or implementing a feature, search the entire repository for its existing UI, domain logic, repository/API, backend function, data model, and tests.
- Never call a feature "missing" solely because one client call site uses a different transport. Distinguish implementation, integration, deployment, and runtime verification.
- Never rebuild existing cancellation, refund, payment, cash-collection, QR, or order-status behavior without proving a specific defect.
- Inspect callers and callees before changing a function signature, Firestore schema, Worker route, or callable contract.
- Prefer minimal, focused diffs. Avoid unrelated formatting, renaming, redesign, or cleanup.

## Financial and commerce safety

- DEMO and REAL merchants/data must remain strictly separated.
- Use fake/demo funds and simulated payment providers only. Do not introduce real payment processing or paid credentials.
- Preserve server-side authorization, validation, idempotency, atomic Firestore transactions, inventory/stock rules, cashback rules, order status history, and auditability.
- Never replace an atomic wallet/payment operation with a sequence of unprotected writes.
- Never trust client-supplied amount, merchant identity, price, stock, delivery distance, or payment status without server validation.
- Keep QR payloads limited to the intended safe order data; do not put credentials or private personal data in QR content.
- Preserve traceability fields needed by Admin.

## External services and destructive actions

- Do not deploy Cloudflare Workers or Firebase Functions.
- Do not publish or change Firebase security rules.
- Do not write, delete, migrate, or bulk-modify live Firebase data.
- Do not change production secrets, GitHub Actions secrets, signing keys, or app identifiers.
- Do not merge pull requests or delete branches.
- These actions require explicit owner authorization.

## Android apps and signing

- Principal and Admin are separate apps; preserve both build targets and package identifiers.
- The owner does not use Android Studio; prefer GitHub Actions and downloadable artifacts with clear instructions.
- A temporary/debug-signed APK is not update-compatible with an app signed by the original certificate.
- Do not claim update-install compatibility unless the generated APK's signing certificate has been verified against the expected original SHA-256 fingerprint documented in `OMNI_HANDOFF.md`.
- Do not expose or commit keystores, passwords, service account files, API tokens, or other secrets.

## UX and product requirements

- Maintain OMNI's dark cyber-neon visual identity and compact, professional control-center style.
- Avoid oversized generic cards, duplicate modules, and generic traceability labels.
- Admin is primarily read-only and analytics-focused.
- Show the account holder's name as primary and alias as secondary in account/balance views.
- Do not replace the requested custom cyber-neon Admin map with a generic default map without approval.

## Evidence and reporting

- Separate facts into: implemented, integrated, deployed, tested, and runtime-verified.
- A source-code implementation is not proof of successful integration or runtime behavior.
- A successful workflow is not proof of device runtime behavior. A cancelled workflow is not a pass.
- Before marking work complete, run relevant tests/builds and inspect their logs/artifacts.
- Report exact commit SHA, changed files, checks executed, results, and remaining blockers.
- If the repository does not provide enough evidence, say "unverified" and explain what evidence is needed. Do not guess.
- Keep user-facing updates concise and concrete; do not repeatedly ask the user to say "continue".

## Infrastructure, Android signing, and Firebase references

Before investigating or changing infrastructure, Android signing, Firebase, or external API integrations, consult the relevant documentation in [`docs/infraestructura/credenciales/INDICE.md`](../docs/infraestructura/credenciales/INDICE.md), including:

- [`firebase.md`](../docs/infraestructura/credenciales/firebase.md)
- [`android-signing.md`](../docs/infraestructura/credenciales/android-signing.md)
- [`apis.md`](../docs/infraestructura/credenciales/apis.md)

Use the documents as a safety and integration reference, not as evidence that an item is currently integrated or deployed. Never copy credential values or sensitive material into the repository.
