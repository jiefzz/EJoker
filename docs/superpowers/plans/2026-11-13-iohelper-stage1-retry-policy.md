# IOHelper Stage 1: Bounded Retry + Full Jitter + retryOn + ControlTable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `IOHelper`'s unbounded retry with a configurable, bounded retry policy that uses Full Jitter backoff, a `retryOn` exception classifier, and `ControlTable`-backed defaults.

**Architecture:** Add a `RetryPolicy` abstraction and `ControlTable` configuration keys. Integrate it into `IOHelper` behind the existing public overloads so `retryWhenFailed=true` now means "bounded retry using the global policy". Non-retryable exceptions and exhausted budgets fall through to the existing `faildAction` terminal path.

**Tech Stack:** Java 8+, JUnit 5, SLF4J, existing `ControlTable`.

**Spec:** Conversation-approved Stage 1 design: default max attempts 5, base delay 200ms, max delay 5s, Full Jitter enabled, retry only `IOException`/`IOExceptionOnRuntime`.

## Global Constraints

- Existing `IOHelper` public overload signatures must remain unchanged.
- `retryWhenFailed=true` must become bounded, not infinite.
- Non-retryable exceptions and exhausted attempts must enter `faildAction` terminal handling.
- `completeAction` and `faildAction` must run only after `markFinish()`.
- `ControlTable` keys must be registered with validators and sensible defaults.
- Tests must not rely on wall-clock timing beyond bounded ranges; Full Jitter must be injectable for deterministic assertions.

## Review Focus

- `retryWhenFailed=true` with repeated `IOExceptionOnRuntime`: attempts are bounded and terminal failure occurs.
- `retryWhenFailed=true` with a non-IO runtime exception: no retry, immediate terminal failure.
- Backoff delays: within `[0, cap]` and decreasing variance across retries.
- `ControlTable` overrides: custom values change policy behavior.
- Existing Stage 0 regressions: complete-callback failure still must not replay successful IO.

---

### Task 1: `RetryPolicy` + `ControlTable` Configuration

**Files:**
- Create: `ejoker-common/src/main/java/pro/jk/ejoker/common/system/task/io/RetryPolicy.java`
- Modify: `ejoker-common/src/main/java/pro/jk/ejoker/common/system/component/ControlTable.java`
- Test: `ejoker-common/src/test/java/pro/jk/ejoker/common/system/task/io/RetryPolicyTest.java`

**Interfaces:**
- Produces: `RetryPolicy` public API:
  - `int getMaxAttempts()`
  - `long getBaseDelayMillis()`
  - `long getMaxDelayMillis()`
  - `boolean isFullJitterEnabled()`
  - `boolean retryOn(Throwable t)`
  - `long nextBackoffMillis(int attemptIndex)`
  - static `RetryPolicy defaultPolicy()`
  - static `RetryPolicy fromControlTable()`

- [ ] **Step 1: Write failing tests**

```java
testDefaultPolicyBounds()
testRetryOnClassification()
testFullJitterBackoffBounds()
testControlTableOverride()
```

Assertions:
- defaults are 5 / 200ms / 5s / true;
- `IOException`, `IOExceptionOnRuntime` are retryable; `RuntimeException` is not;
- backoff is in `[0, maxDelay]`;
- ControlTable overrides are honored.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ejoker-common test -Dtest=RetryPolicyTest`
Expected: compile failure, `RetryPolicy` not found.

- [ ] **Step 3: Implement `RetryPolicy` and register ControlTable keys**

Keys:
- `ejoker.io.retry.maxAttempts`
- `ejoker.io.retry.baseDelayMillis`
- `ejoker.io.retry.maxDelayMillis`
- `ejoker.io.retry.fullJitter`

Use a pluggable `LongSupplier`/random source in tests to keep jitter deterministic.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl ejoker-common test -Dtest=RetryPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ejoker-common/src/main/java/pro/jk/ejoker/common/system/task/io/RetryPolicy.java \
        ejoker-common/src/main/java/pro/jk/ejoker/common/system/component/ControlTable.java \
        ejoker-common/src/test/java/pro/jk/ejoker/common/system/task/io/RetryPolicyTest.java
git commit -m "feat(io): add bounded RetryPolicy with ControlTable defaults"
```

---

### Task 2: Integrate `RetryPolicy` into `IOHelper`

**Files:**
- Modify: `ejoker-common/src/main/java/pro/jk/ejoker/common/system/task/io/IOHelper.java`
- Test: `ejoker-common/src/test/java/pro/jk/ejoker/common/system/task/io/IOHelperStage1Test.java`

**Interfaces:**
- Consumes: `RetryPolicy` from Task 1.
- Produces:
  - `IOHelper` uses `RetryPolicy` when `retryWhenFailed=true`.
  - `IOHelperContext` gains a `RetryPolicy` reference.
  - `executeRetryAction()` consults `retryOn`, `maxAttempts`, `nextBackoffMillis`.

- [ ] **Step 1: Write failing tests**

```java
testBoundedRetryOnRepeatedIOException()
testNonRetryableExceptionGoesTerminal()
testRetryBudgetExhaustionCallsFailedAction()
testBackoffIsAppliedBetweenAttempts()
testStage0RegressionStillHolds()
```

Assertions:
- repeated IO exception stops after 5 attempts;
- `IllegalArgumentException` does not retry;
- exhausted budget triggers `faildAction` exactly once;
- backoff sleeps are observable but bounded;
- complete-callback failure still does not replay `mainAction`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ejoker-common test -Dtest=IOHelperStage1Test`
Expected: FAIL because current implementation retries indefinitely or ignores policy.

- [ ] **Step 3: Modify `IOHelper`**

- Add `RetryPolicy` field to `IOHelperContext`.
- Replace unbounded `executeRetryAction` logic:
  - if `retryOn` returns false, go terminal;
  - if attempt count reaches `maxAttempts`, go terminal;
  - otherwise sleep `nextBackoffMillis(currentRetryTimes)`.
- Preserve `markFinish()` before `completeAction` and `faildAction`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl ejoker-common test -Dtest=IOHelperStage1Test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ejoker-common/src/main/java/pro/jk/ejoker/common/system/task/io/IOHelper.java \
        ejoker-common/src/test/java/pro/jk/ejoker/common/system/task/io/IOHelperStage1Test.java
git commit -m "feat(io): bound IOHelper retries with RetryPolicy"
```

---

### Task 3: Full Regression and Documentation Sweep

**Files:**
- Modify: none expected.
- Test: existing `IOHelperStage0Test`, `IOHelperStage1Test`, `RetryPolicyTest`.

**Interfaces:**
- Consumes: completed Task 1 and Task 2.

- [ ] **Step 1: Run all IO-related tests**

Run: `mvn -pl ejoker-common test -Dtest='IOHelper*Test,RetryPolicyTest'`
Expected: PASS.

- [ ] **Step 2: Run module tests**

Run: `mvn -pl ejoker-common test`
Expected: PASS.

- [ ] **Step 3: Commit any residual fixes**

```bash
git add -A
git commit -m "test(io): verify bounded retry integration"
```
