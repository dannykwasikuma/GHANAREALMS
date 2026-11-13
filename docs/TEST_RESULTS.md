# Test Results
PASSED: all 14 migrations apply in order to a fresh PostgreSQL 16; seeds re-runnable; claim-lease SQL executes; Java files syntax-checked (`javac -proc:none`).
NOT RUN (sandbox has no network): npm install, lint, type-check, unit tests, `next build`, Maven builds, Paper startup, Paystack/Hubtel flows, email, linking, delivery end-to-end, refund, world verification.
No result above should be read as proof of production readiness.
