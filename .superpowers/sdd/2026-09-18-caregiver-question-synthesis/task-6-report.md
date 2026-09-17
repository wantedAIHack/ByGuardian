# Task 6 Report — caregiver-confirmed question list and preparation APIs

- **Status:** Complete
- **Commit:** `feat: let caregivers confirm, edit and regenerate their visit question list`
- **Tests:** `cd backend && ./gradlew clean test` → `BUILD SUCCESSFUL`, 297 tests, 0 failures, 0 errors

## Implemented

- Added PostgreSQL and H2 V3 migrations plus `CaseEntity` JSON/timestamp fields for the confirmed question list.
- Added `ConfirmedItem`, `SaveQuestionsRequest`, and `QuestionListService`. Visible items use sentence-hash IDs before confirmation, move to sequential `cN` IDs on save, retain server-owned origin/basis only for current matching IDs, and treat unknown/stale IDs as caregiver questions without basis.
- Added `items`, generation status, edited, and suggestion flags to `GET /me/prep-card`; existing `questions`, `extraQuestions`, evidence, and therapist glance fields remain present.
- Added `PUT /me/prep-card/questions` and `POST /me/prep-card/regenerate`. Saving enforces eight items, nonblank sentences, a 200-character limit, and rejects null array entries. Regeneration preserves caregiver questions as legacy extra questions and refreshes a cache that is not newer than the confirmation.
- Kept legacy `PUT /me/prep-card/extra` compatible. For confirmed lists it replaces caregiver entries, renumbers every retained and replacement item sequentially to prevent duplicate IDs, and rejects a combined list over eight items.
- Used the existing case pessimistic-write lock for save, regenerate, and legacy extra-question mutation paths, matching the existing case → snapshot → cache refresh order.

## TDD evidence

1. Added controller tests for the new response contract, legacy items, trusted-basis saves, validation (including null items), stale suggestions/regeneration, legacy replacement ID renumbering, and combined maximum.
2. The first targeted run failed to compile because the required `MutableClock.advanceSeconds` helper did not yet exist. After adding that test helper, the same targeted suite failed as expected with seven missing `items`/endpoint behavior failures.
3. After implementation, `./gradlew :api:test --tests 'nextvisit.api.questions.PrepCardControllerTest'` passed all 10 tests.
4. Fresh `./gradlew clean test` passed all 297 tests with no failures or errors; this exercised the new H2 Flyway migration.

## Concerns

None. The follow-on Task 7 can consume `QuestionListService.visible(...)` and `ConfirmedItem.isCaregiver()` directly.
