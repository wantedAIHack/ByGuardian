# Interactive Demo Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let hackathon judges complete normal onboarding from week 1 and advance only their demo case one week at a time while all existing record and report logic uses the simulated date.

**Architecture:** Persist a demo flag and virtual date per case, then route every case-sensitive date calculation through `CaseTimeline`. Reuse the existing onboarding component with a mode-specific endpoint, draft key, and session-scoped demo token; expose one authenticated advance endpoint and a shared demo banner.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Flyway, PostgreSQL/H2, React 19, TypeScript, TanStack Query, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-19-interactive-demo-mode-design.md`

## Global Constraints

- General cases must continue to use the real Seoul clock.
- Demo cases must use only their persisted virtual date for week and visit calculations.
- A demo can advance exactly seven days only after the current week has a snapshot.
- Existing onboarding, questionnaire v2, recording, analysis, and reporting logic must be reused.
- Do not add demo-only analysis, LLM behavior, arbitrary date travel, or automatic data cleanup.
- Demo authentication must not overwrite an existing general guardian token.

---

### Task 1: Persist and resolve case-specific time

**Files:**
- Create: `backend/api/src/main/resources/db/migration/postgresql/V4__interactive_demo.sql`
- Create: `backend/api/src/main/resources/db/migration/h2/V4__interactive_demo.sql`
- Create: `backend/api/src/main/java/nextvisit/api/common/CaseTimeline.java`
- Modify: `backend/api/src/main/java/nextvisit/api/cases/CaseEntity.java`
- Test: `backend/api/src/test/java/nextvisit/api/common/CaseTimelineTest.java`
- Test: `backend/api/src/test/java/nextvisit/api/PersistenceTest.java`

**Interfaces:**
- Produces: `LocalDate CaseTimeline.today(CaseEntity kase)` and `int CaseTimeline.currentWeek(CaseEntity kase)`.
- Produces: `CaseEntity.isDemoMode()`, `getDemoToday()`, and `advanceDemoWeek()`.

- [x] Write failing tests proving ordinary cases use the fixed Clock, demo cases use `demoToday`, and advancing changes only the demo date by seven days.
- [x] Add the two V4 migrations with `demo_mode`, `demo_today`, and a consistency check constraint.
- [x] Add demo fields and guarded advancement to `CaseEntity`, keeping the existing constructor as the ordinary-case path.
- [x] Implement `CaseTimeline` and run the focused timeline and persistence tests.

### Task 2: Create week-one demos and advance them safely

**Files:**
- Modify: `backend/api/src/main/java/nextvisit/api/cases/CaseService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/cases/CaseRepository.java`
- Modify: `backend/api/src/main/java/nextvisit/api/cases/MeResponse.java`
- Modify: `backend/api/src/main/java/nextvisit/api/demo/DemoController.java`
- Replace: `backend/api/src/main/java/nextvisit/api/demo/DemoService.java`
- Delete: `backend/api/src/main/java/nextvisit/api/demo/DemoResponse.java`
- Modify: `backend/api/src/main/java/nextvisit/api/demo/DemoSeedWriter.java` (internal test fixture only)
- Test: `backend/api/src/test/java/nextvisit/api/demo/DemoFlowTest.java`

**Interfaces:**
- Consumes: `CaseTimeline` from Task 1.
- Produces: `POST /demo/cases`, `POST /me/demo/advance`, and `MeResponse.demoMode/canAdvanceDemo`.

- [x] Rewrite `DemoFlowTest` to post a complete onboarding body, assert week 1 and the selected visit date, reject advance before a current snapshot, advance to week 2 after baseline, and reject the endpoint for a normal case.
- [x] Extract one internal onboarding path in `CaseService` that creates either an ordinary or demo case without duplicating baseline validation.
- [x] Implement the two demo endpoints and lock the case row before checking the latest snapshot and advancing.
- [x] Return case-specific `today`, `week`, `demoMode`, and `canAdvanceDemo` from `/me`.
- [x] Remove the public six-week seed endpoint, retain its writer behind a test-only controller, and run the focused API tests.

### Task 3: Apply virtual time to all existing server flows

**Files:**
- Modify: `backend/api/src/main/java/nextvisit/api/snapshots/SnapshotService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/progress/ProgressService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/PrepCardService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/therapist/TherapistSummaryService.java`
- Test: `backend/api/src/test/java/nextvisit/api/demo/DemoFlowTest.java`
- Test: `backend/api/src/test/java/nextvisit/api/snapshots/SnapshotControllerTest.java`

**Interfaces:**
- Consumes: `CaseTimeline.currentWeek(CaseEntity)` from Task 1.
- Produces: consistent demo week values across save, progress, prep card, and therapist summary.

- [x] Add a failing integration path that advances a demo, saves week 2, and observes week 2 in progress, prep card, trajectory, and therapist summary.
- [x] Replace every case-sensitive direct `WeekCalculator.currentWeek(startDate)` call with `CaseTimeline.currentWeek(kase)`.
- [x] Validate visit-date edits against `CaseTimeline.today(kase)` and run the focused API tests.

### Task 4: Isolate demo browser authentication and reuse onboarding

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/lib/draft.ts`
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/screens/Onboarding.tsx`
- Modify: `frontend/src/screens/Demo.tsx`
- Modify: `frontend/src/routes.tsx`
- Test: `frontend/src/lib/api.test.ts`
- Test: `frontend/src/screens/OnboardingBaseline.test.tsx`
- Test: `frontend/src/screens/Demo.test.tsx`

**Interfaces:**
- Produces: `setDemoToken`, `clearDemoToken`, `hasDemoToken`; `Onboarding({ catalog, mode })`; `/demo/onboarding`.
- Consumes: `POST /demo/cases` from Task 2.

- [x] Write failing tests for demo-token precedence, ordinary-token preservation, separate drafts, and the demo onboarding endpoint.
- [x] Add session-scoped demo token functions and make unauthorized cleanup remove only the active token.
- [x] Parameterize `Onboarding` by mode while sharing every visible onboarding step and questionnaire.
- [x] Change `/demo` to an introduction and route its start button to `/demo/onboarding`.
- [x] Run the focused Vitest files.

### Task 5: Add the persistent demo controls

**Files:**
- Create: `frontend/src/ui/DemoBanner.tsx`
- Create: `frontend/src/ui/DemoBanner.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/lib/queries.ts`
- Modify: `frontend/src/lib/types.ts`

**Interfaces:**
- Consumes: `Me.demoMode`, `Me.canAdvanceDemo`, `POST /me/demo/advance`, and demo token helpers.
- Produces: app-wide demo date/week status, guarded advance, error display, and demo exit.

- [x] Write failing component tests for recorded and unrecorded weeks, pending double-click protection, successful cache refresh, failure copy, and exit preserving the ordinary token.
- [x] Implement `useAdvanceDemo` with `/me` cache update and `['me']` invalidation.
- [x] Render the banner above caregiver routes only when `/me` reports `demoMode=true`.
- [x] Implement `다음 주차로 이동` and `데모 종료`, then run the component tests.

### Task 6: Verify the complete judge journey and documentation

**Files:**
- Replace demo setup in: `frontend/e2e/critical-flow.spec.ts`
- Create: `frontend/e2e/interactive-demo.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: a repeatable browser proof of the public demo journey.

- [x] Write an E2E flow that enters `/demo`, completes all onboarding questions, confirms week 1, advances to week 2, saves a weekly record, advances toward the chosen visit, and opens the therapist summary.
- [x] Assert the displayed virtual date advances seven days, the button is disabled before saving, ordinary dates are never changed, and no horizontal overflow occurs at 390px.
- [x] Update README demo instructions and remove claims that the public demo starts with six seeded weeks.
- [x] Run `./gradlew test --rerun-tasks`, `npm test`, `npm run build`, and the full Playwright suite against the H2 API.
- [x] Run `git diff --check` and inspect the final diff for unintended generated files.
