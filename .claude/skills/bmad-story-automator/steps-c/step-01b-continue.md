---
name: "step-01b-continue"
description: "Handle workflow continuation from previous session"
outputFolder: "{output_folder}/story-automator"
outputFile: "{outputFolder}/orchestration-{epic_id}-{timestamp}.md"
defaultSprintStatusFile: "{implementation_artifacts}/sprint-status.yaml"
preflightStep: "./step-02-preflight.md"
preflightConfigStep: "./step-02a-preflight-config.md"
preflightFinalizeStep: "./step-02b-preflight-finalize.md"
executeStep: "./step-03-execute.md"
executeReviewStep: "./step-03a-execute-review.md"
executeFinishStep: "./step-03b-execute-finish.md"
executeCompleteStep: "./step-03c-execute-complete.md"
wrapupStep: "./step-04-wrapup.md"
stateFilePattern: "{outputFolder}/orchestration-*.md"
stateHelper: "../scripts/story-automator"
ensureMarkerGitignore: "../scripts/story-automator"
deriveProjectSlug: "../scripts/story-automator"
listSessions: "../scripts/story-automator"
sprintCompare: "../scripts/story-automator"
tmuxCommands: "../data/tmux-commands.md"
# Optional: provided by workflow.md when using Resume mode (skips state search)
resumeStatePath: ""
---

# Step 1b: Continue Previous Session

**Goal:** Load existing state and let user choose how to proceed.

---

## 🚨 BEFORE LOADING: Schema Validation Gate

**CRITICAL:** A v0-schema state document silently broke Epic 3 (commit `55c13ae`'s retro). Before touching any state, detect the drift and abort cleanly so the user can fall back to the direct-cycle path documented in CLAUDE.md / `epic-3-retrospective.md`.

**Valid signals (v1 schema):**
- Frontmatter splits into two YAML blocks separated by a blank line + second `---` (template has `# Orchestration State Document` heading between them).
- `policyVersion: 1` present.
- `agentConfig` block with `defaultPrimary` and `defaultFallback`.
- `complexityFile` points to a JSON ≥ 100 bytes (the helper writes the per-story matrix there; empty = preflight was bypassed).
- `currentStep ∈ {null, "done"} ∪ workflow.sequence` (sequence lives in `data/orchestration-policy.json` → `workflow.sequence`).

**Run BEFORE state load:**

```bash
# Self-flagged drift (Epic 3 retro documented this exact marker)
if grep -q "^deviationNote:" "$state_file" 2>/dev/null; then
  echo "ABORT: state document carries deviationNote (manual edit, not v1)" >&2
  exit 11
fi

# Single-`---` block v0 signature: frontmatter has only ONE `---` line at the top
# of the file (no closing `---` before the body). v1 wraps two blocks.
# Better discriminator: v1 inserts the literal heading `# Orchestration State Document`
# on line 2 (between the two `---` fences); v0 goes straight to `epic:` on line 2.
if ! sed -n '2p' "$state_file" 2>/dev/null | grep -q '^# Orchestration State Document$'; then
  echo "ABORT: state document is missing the v1 '# Orchestration State Document' heading on line 2 (v0 drift)" >&2
  exit 12
fi

# policyVersion missing or wrong
if ! grep -q "^policyVersion:[[:space:]]*1[[:space:]]*$" "$state_file" 2>/dev/null; then
  echo "ABORT: state document is missing policyVersion: 1" >&2
  exit 13
fi

# complexityFile points to a real, non-empty JSON (v0 drifted → 0 bytes)
complexity_path=$(grep -E "^complexityFile:" "$state_file" | head -1 | sed -E 's/^complexityFile:[[:space:]]*"?([^"]+)"?/\1/')
if [ -z "$complexity_path" ] || [ ! -s "$complexity_path" ] || [ "$(wc -c < "$complexity_path" 2>/dev/null | tr -d ' ')" -lt 100 ]; then
  echo "ABORT: complexityFile is empty or missing (v0 drift — preflight was bypassed)" >&2
  exit 14
fi

# currentStep must be a known step-file name (or null / "done" terminal).
# The field stores the orchestrator step FILE (e.g. 'step-02-preflight'), not a
# workflow.sequence token. v0-drift examples: 'preflight' (file removed in v1),
# 'create' (sequence step, never stored here), arbitrary strings from hand-edits.
current_step=$(grep -E "^currentStep:" "$state_file" | head -1 | sed -E 's/^currentStep:[[:space:]]*"?([^"]+)"?/\1/')
valid_steps=$(ls -1 "../steps-c" 2>/dev/null | sed 's/\.md$//' | paste -sd'|' -)
if [ -n "$current_step" ] && [ "$current_step" != "null" ] && [ "$current_step" != "done" ]; then
  if ! echo "$current_step" | grep -qE "^($valid_steps)$"; then
    echo "ABORT: currentStep '$current_step' is not a known step file (valid: $valid_steps) or 'done'" >&2
    exit 15
  fi
fi

# agentConfig.perTask must be block-style (YAML map) — v0 drift is flow-style
# `perTask: {retro:}` which the runner can't iterate. Detect flow-style braces on
# the same line as `perTask:`.
if grep -E "^[[:space:]]+perTask:[[:space:]]*\{[^}]*\}" "$state_file" >/dev/null 2>&1; then
  echo "ABORT: agentConfig.perTask uses flow-style braces (v0 drift — must be block YAML)" >&2
  exit 16
fi
```

**On any ABORT:** print, recommend the user fall back to the direct-cycle path (CLAUDE.md / `epic-3-retrospective.md` "Direct cycle path" section), and **HALT before** state load. Do NOT auto-purge or rewrite the file — it may hold retrospective value.

---

## Do

### 1. Load State Document

**IF `{resumeStatePath}` is provided (from workflow.md Resume routing):**
Use it directly: `state_file="{resumeStatePath}"`

**ELSE (called from step-01-init or no path provided):**
Find the most recent incomplete state document using `{stateFilePattern}`:

```bash
result=$("{stateHelper}" orchestrator-helper state-latest-incomplete "{outputFolder}")
state_file=$(echo "$result" | jq -r '.path // empty')
```

**IF state_file is empty:** Display "No incomplete orchestration found." and HALT.

**Then extract from state_file:**

- `epic`, `epicName`, `storyRange`
- `currentStep`, `status`
- `stepsCompleted`, `storiesCompleted`
- Last action from action log

Use deterministic summary:

```bash
summary=$("{stateHelper}" orchestrator-helper state-summary "$state_file")
```

### 2. Verify Against Sprint Status

Load `{defaultSprintStatusFile}`.

**Compare with state document (run in parallel with session inventory):**

- Check if earlier stories (before `currentStory`) are marked `done` in sprint-status
- If any earlier stories are NOT `done`:

  ```
  **Warning:** Stories {X, Y} are not complete in sprint-status.yaml.

  [B]atch them first - Add to queue before continuing
  [S]kip - Continue from current story anyway
  ```

  **Wait.**
  - If B: Add incomplete stories to beginning of queue
  - If S: Note skip in action log, continue

Use deterministic parallel baseline:

```bash
tmp_compare=$(mktemp)
tmp_sessions=$(mktemp)

("{sprintCompare}" sprint-compare --state "$state_file" --sprint "{defaultSprintStatusFile}" > "$tmp_compare") &
compare_pid=$!

project_slug=$(echo "$("{deriveProjectSlug}" derive-project-slug --project-root "{project-root}")" | jq -r '.slug')
("{listSessions}" list-sessions --slug "$project_slug" > "$tmp_sessions") &
sessions_pid=$!

wait "$compare_pid"
wait "$sessions_pid"

compare=$(cat "$tmp_compare")
sessions=$(cat "$tmp_sessions")
rm -f "$tmp_compare" "$tmp_sessions"

incomplete=$(echo "$compare" | jq -r '.incomplete | join(", ")')
session_count=$(echo "$sessions" | jq -r '.count')
```

### 3. Check Active Sessions

Using `{tmuxCommands}`, check for existing T-Mux sessions for THIS PROJECT ONLY.

**Generate project slug first:**

```bash
project_slug=$(echo "$("{deriveProjectSlug}" derive-project-slug --project-root "{project-root}")" | jq -r '.slug')
```

**Then list sessions matching:** `sa-{project_slug}-*`

This ensures we only see sessions spawned by THIS project's story-automator, not sessions from other projects.

Use `sessions` and `session_count` from step 2 parallel baseline.

### 4. Present Status

```
**Resuming: {epicName}**

Status: {status}
Progress: {storiesCompleted}/{totalStories} stories
Current: Story {N}, Step: {currentStep}
Last action: {lastAction}

Active sessions: {count or 'None'}
```

### 5. Present Options

```
[R]esume - Continue from where you left off
[V]iew - See action log details
[M]odify - Change overrides or context
[S]tart Over - Restart this epic (keeps backup)
[X]Abort - Cancel orchestration
```

**Wait for user input.**

#### Menu Handling Logic:

- IF R: Create marker file, then route based on `status` and `currentStep`:
  - READY → `{preflightFinalizeStep}`
  - INITIALIZING → `{preflightConfigStep}`
  - IN_PROGRESS / PAUSED → route by `currentStep`:
    - `step-03-execute` or `create` or `dev` → `{executeStep}`
    - `step-03a-execute-review` or `auto` or `review` → `{executeReviewStep}`
    - `step-03b-execute-finish` or `commit` or `retro` → `{executeFinishStep}`
    - `step-03c-execute-complete` → `{executeCompleteStep}`
    - (default) → `{executeStep}`
  - EXECUTION_COMPLETE → `{wrapupStep}`
  - COMPLETE → `{wrapupStep}`
  - ABORTED → display warning and redisplay this menu
- IF V: Show last 20 action log entries, then redisplay this menu
- IF M: Allow override changes, save, then redisplay this menu
- IF S: Rename state to `.backup-{timestamp}` then load `{preflightStep}` (new state will be created at `{outputFile}`)
- IF X: Set status="ABORTED", display confirmation, end workflow
- IF Any other: help user respond, then redisplay this menu

#### EXECUTION RULES:

- ALWAYS halt and wait for user input after presenting menu
- ONLY route to a step after handling the selected option
- After non-routing options, return to this menu
- Keep prompts concise; if user is unsure, ask one clarifying question before redisplaying options

### 6. Handle Choice

| Choice | Action                                                                      |
| ------ | --------------------------------------------------------------------------- |
| **R**  | **First:** Create marker file (see below), **then** route based on `status` |
| **V**  | Show last 20 action log entries → redisplay options                         |
| **M**  | Allow override changes, save → redisplay options                            |
| **S**  | Rename state to `.backup-{timestamp}` → `{preflightStep}`                   |
| **X**  | Set status="ABORTED", display confirmation, end workflow                    |

#### On [R]esume: Create Marker File BEFORE Routing

**CRITICAL:** Only create marker file when user confirms resume. This prevents stop hook from firing during menu wait.

Create the active runtime marker with orchestration context:

```json
{
  "epic": "{epic}",
  "currentStory": "{currentStory}",
  "storiesRemaining": {remaining_count},
  "stateFile": "{state_document_path}",
  "startedAt": "{timestamp}"
}
```

Use deterministic marker creation:

```bash
marker_info=$("{stateHelper}" orchestrator-helper marker path)
marker_entry=$(echo "$marker_info" | jq -r '.entry')
"{ensureMarkerGitignore}" ensure-marker-gitignore --gitignore ".gitignore" --entry "$marker_entry"

"{stateHelper}" orchestrator-helper marker create --epic "{epic}" --story "{currentStory}" \
  --remaining {remaining_count} --state-file "{state_document_path}" \
  --project-slug "$project_slug" --pid "$$" --heartbeat "{timestamp}"
```

**Then** route per Menu Handling Logic in section 5 above.

---

## Then

→ Load appropriate step based on choice
