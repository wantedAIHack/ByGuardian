# Selfcare Questionnaire V2 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent backend task and review. Root implements frontend in parallel against the frozen contract. Steps use checkbox syntax.

**Goal:** Apply approved activity-specific questions and preserve faithful medical reports before September 20.
**Architecture:** Optional versioned questionnaire on each of the four existing selfcare categories; structured answers stored alongside backward-compatible legacy fields. Report observations are descriptive. No new analysis rules.
**Tech Stack:** Java 21 / Spring Boot / React / TypeScript / Vitest.
**Spec:** docs/superpowers/specs/2026-09-19-selfcare-questionnaire-v2-design.md

## Global Constraints
- No new automatic analysis rules. Preserve unchanged-item analysis and existing LLM note synthesis.
- No deployment or service restart. Existing main checkout .gitignore change is untouched.
- Use current isolated worktree .worktrees/warm-observation-ui, which holds running-service implementation.
- v1 and v2 are never compared or relabeled as each other.

### Task 1: Backend questionnaire, validation, reports, analysis boundary
Files: backend/api/src/main/java/nextvisit/api/catalog/*, snapshots/*, engine/EngineBridge.java, progress/TrajectoryMapper.java, cases/* and corresponding tests. Own backend/ only.
Interfaces: exact catalog/input/trajectory shapes in spec; Me.questionnaireUpgradeRequired optional boolean.
- [ ] Add tests: v2 round trip and validation; mixing unknown and rank fails; inactive feeding assistance fails or is stripped; legacy labels remain on historical records; revised v1 values stop contributing to current analysis; carry keeps answers marked CARRIED.
- [ ] Run focused Gradle tests and observe missing behavior.
- [ ] Implement single server-owned questionnaire catalog, validated v2 input/storage, no cross-version carry/downgrade, backward compatibility.
- [ ] Extend report trajectory and Me upgrade flag. Existing template/LLM flow continues for unchanged data and notes.
- [ ] Run backend full test suite on Java 21; report changes and test results.

### Task 2: Frontend questionnaire input and report
Files: frontend/src/lib/types.ts, questionnaire.ts/test, record.ts/test, onboarding.ts/test, ui/Questionnaire.tsx/test, screens/Record.tsx, Onboarding.tsx, Trajectory.tsx, Therapist.tsx and tests.
Interfaces: consume Task 1 contract. Generic questionnaire rendering and condition evaluation; revised categories send null legacy axes and sanitized answer map.
- [ ] Add failing behavior tests: incomplete main answers block Next; tube-only skips assistance; changed parent strips descendants; exclusive multi-select; request serialization preserves version/notes.
- [ ] Implement shared questionnaire editor and integrate both entry flows. Respect questionnaireUpgradeRequired as full recheck.
- [ ] Render versioned observations and transition notice in full record and clinician report, even when unchanged.
- [ ] Run frontend tests/typecheck/build and end-to-end checks against actual backend where possible.

### Task 3: Integration review and handoff
- [ ] Review backend/frontend wire contract and mixed-history behavior independently.
- [ ] Fix material findings; rerun appropriate tests.
- [ ] Update README for revised question system and existing analysis limitations.
- [ ] Report exact validation, changed behavior, and deployment state without claiming deployment.
