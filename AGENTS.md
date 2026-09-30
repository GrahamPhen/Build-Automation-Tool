# Project agent policy

Graham requires this workflow for every task in Build-Automation-Tool:

- Use ChatGPT Sol 6.1 High subagents (`gpt-6.1-sol`, reasoning effort `high`) as the primary implementation workers.
- Use Astra Extra High (`gpt-6-astra`, reasoning effort `xhigh`) as the project manager and reviewer.
- Delegate bounded subtasks with only the necessary context. When selecting model overrides, use `fork_turns="none"` or a small history slice.
- Keep handoffs compact: objective, changed files, decisions, verification, unresolved issues, and next action.
- Use one bounded Sol worker per routine heartbeat to read the current brief in `HANDOFF.md` section 0, runtime state, and new log delta, and check the existing watcher. Return one compact result and preserve the runtime cursor on every heartbeat.
- Keep the same worker for the same defect; use fresh compact context for unrelated small tasks. Add workers only for independent material work.
- The manager reviews each result and relevant verification once before declaring completion. Do not duplicate the worker's full reads unless missing evidence or risk requires it.
- Prefer watcher/completion events and bounded waits over repeated status reads. Send worker messages only for a blocker or new steering; avoid nudges and status-only messages. Keep required user progress updates concise.
- Batch documentation, runtime summaries, and memory checkpoints at meaningful milestones; cursor persistence is still required every heartbeat. Preserve all safety, testing, review, commit/push, and mirror requirements, and the authorized monitoring interval.

These are project instructions; they do not indicate that app or global model settings have changed. Graham's explicit model preferences take precedence over generic model-selection guidance.
