# Task 4 Report: Minimal Question-Rewrite Prompt

## Implementation

Implemented `nextvisit.api.llm.QuestionRewritePrompt` as a serialization-only boundary component.

It builds:
- `Prompt(systemMessage, userMessage)`
- `build(List<QuestionCacheBody.Q>, Optional<String>)`

The user payload now serializes only `rank` and `templateSentence` from `QuestionCacheBody.Q`.
The system message includes `/no_think` and appends only a retry rule name when `retryRule` is present.

## Files

- Added `backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java`
- Added `backend/api/src/test/java/nextvisit/api/llm/QuestionRewritePromptTest.java`

## RED / GREEN Evidence

RED command:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionRewritePromptTest
```

Observed failure:
- `compileTestJava FAILED`
- `cannot find symbol`
- `class QuestionRewritePrompt`

GREEN command:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionRewritePromptTest
```

Observed success:
- `QuestionRewritePromptTest > retryAddsOnlyTheRuleNameAndKeepsTheSameUserJson() PASSED`
- `QuestionRewritePromptTest > sendsOnlyRankAndTemplateSentence() PASSED`
- `BUILD SUCCESSFUL`

## Self-Review

- The user message boundary is minimal: only `rank` and `templateSentence` are serialized.
- The test proves the sentinel values from `type`, `items`, `signal`, `sentence`, and `source` do not appear in the serialized user JSON.
- The retry path adds only the rule name to the system message and does not reuse rejected content.
- The component does not add provider logic, HTTP behavior, or any other trust-boundary expansion.

## Concerns

- None for this task. Later LLM integration work should keep the retry message free of rejected sentence content and preserve the same JSON shape.
