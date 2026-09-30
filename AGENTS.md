# Project agent policy

Graham requires this workflow for every task in Build-Automation-Tool:

- Use ChatGPT Sol 6.1 High subagents (`gpt-6.1-sol`, reasoning effort `high`) as the primary implementation workers.
- Use Astra Extra High (`gpt-6-astra`, reasoning effort `xhigh`) as the project manager and reviewer.
- Delegate bounded subtasks with only the necessary context. When selecting model overrides, use `fork_turns="none"` or a small history slice.
- Keep handoffs compact: objective, changed files, decisions, verification, unresolved issues, and next action.
- The manager must review worker results and relevant verification before declaring completion.

These are project instructions; they do not indicate that app or global model settings have changed. Graham's explicit model preferences take precedence over generic model-selection guidance.
