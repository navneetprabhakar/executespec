# Code Validation — Requirements

## Overview

Per-file and per-project code validation with language-specific toolchains, sequential gate execution, and automatic retry with model escalation on failure.

---

## Features

### 1. Validator Library

**Requirements:**
- Pluggable architecture: CodeValidator → LanguageToolchain → GateChain
- Four validation levels: SYNTAX, TYPE, LINT, FULL (including tests)
- Four validation gates executed sequentially (fail-fast):
  1. SYNTAX — parser/syntax check
  2. TYPE_CHECK — type checker (javac, mypy, tsc)
  3. LINT — linter rules (ESLint, ruff)
  4. UNIT_TEST — test suite execution
- Support file-level and project-level validation
- Return structured ValidationResult with verdict (PASS/WARN/FAIL), errors, warnings, gate results

### 2. Language Toolchains

**Java (JavaToolchain):**
- SYNTAX: delegated to TYPE (Java can't compile single files in isolation)
- TYPE: `mvn compile -q -B` or `gradle compileJava -q`
- LINT: skipped in v1 (Checkstyle planned)
- UNIT_TEST: `mvn test -B -q -Dtest={TestClassName}` (only if matching test exists)

**TypeScript (TypeScriptToolchain):**
- SYNTAX: delegated to TYPE
- TYPE: `npx tsc --noEmit --pretty false` (requires tsconfig.json)
- LINT: `npx eslint --format compact {file}` (requires ESLint config)
- UNIT_TEST: `npx vitest --run {test}` or `npx jest --run {test}` (auto-detects)

**React (ReactToolchain):**
- Extends TypeScript toolchain for .tsx/.jsx files
- Lint: React hook violations (`rules-of-hooks`, `exhaustive-deps`) are blocking errors
- Other lint issues are warnings (WARN verdict, not FAIL)

**Python (PythonToolchain):**
- SYNTAX: `python -m py_compile {file}`
- TYPE: `mypy {file}`
- LINT: `ruff check {file}`
- UNIT_TEST: `pytest {test_file}` (checks for test_ or _test naming)

### 3. GateChain

**Requirements:**
- Execute gates sequentially, stop on first FAIL
- Return level-based: if requested level is TYPE, stop after TYPE gate passes
- Lint gate special behavior: React hook violations FAIL, other lint errors WARN
- Track per-gate results (passed, duration, errors, raw output)
- Configurable timeout per gate (default: 30s)

### 4. Process Executor

**Requirements:**
- Run external commands via ProcessBuilder
- Configurable timeout per command
- Capture stdout + stderr
- Return structured ProcessResult (exitCode, stdout, stderr, timedOut)

### 5. Output Parsers

**Requirements:**
- Parse compiler/linter/test output into structured ValidationError objects
- Parsers: JavacOutputParser, TscOutputParser, MypyOutputParser, EslintOutputParser, RuffOutputParser, GenericTestOutputParser
- Extract file, line, column, error code, message, suggestion

### 6. Retry with Error Feedback

**Requirements:**
- RetryPromptBuilder formats errors for LLM retry:
  - Structured error list (file, line, gate, message, suggestion)
  - Truncated raw output (3000 chars max)
  - Instructions: fix ONLY reported errors, don't rewrite unaffected files
- Inject feedback into task via `stateStore.markRetryingWithFeedback()`
- Up to `max-retries: 3` attempts per task
- Model escalation on retry (attempt 3: FAST → CAPABLE)

### 7. Validation Metrics

**Requirements:**
- Track ModelTaskMetric per execution: runId, taskId, model, provider, tier, complexity, attempt, passed, errorCount, firstErrorGate, duration
- Store in `model_task_metric` table
- Enable data-driven routing improvements

---

## Configuration

```yaml
executespec:
  validator:
    enabled: true
    per-task-validation: true
    per-task-level: TYPE          # SYNTAX | TYPE | LINT | FULL
    project-validation-level: FULL
    max-retries: 3
    default-timeout: 30s
```

## Key Files

| File | Purpose |
|------|---------|
| `validator/CodeValidator.java` | Interface |
| `validator/CodeValidatorImpl.java` | Implementation — routes to toolchains |
| `validator/gate/GateChain.java` | Sequential gate execution |
| `validator/language/*.java` | Java, TypeScript, Python, React toolchains |
| `validator/execution/ProcessExecutor.java` | External command execution |
| `validator/execution/*OutputParser.java` | Output parsers |
| `validator/prompt/RetryPromptBuilder.java` | Error feedback formatting |
| `validator/model/*.java` | Language, ValidationLevel, ValidationResult, etc. |
