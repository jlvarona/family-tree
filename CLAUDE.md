# CLAUDE.md

@AGENTS.md

@ARCHITECTURE.md

## Claude Code notes

- This file imports AGENTS.md (shared agent rules) and ARCHITECTURE.md; keep project rules there so every agent sees them, and keep this file for Claude-specific notes.
- Development machine is Windows: use `gradlew.bat` (or `./gradlew` from Git Bash). Paths in docs use forward slashes.
- Gradle builds are slow on first run (configuration cache and build cache are enabled); prefer running a single module's tests (`:core:jvmTest`, `:server:test`) over `check` while iterating.
- Before large changes, outline the plan and the files you'll touch; update `docs/concept-architecture.md` (decisions table) when the user settles an open question.
- The original concept doc lives online at https://claude.ai/artifact/UEGgKDMKLry47RfF9imFtZ; `docs/concept-architecture.md` is the in-repo source of truth from now on.

### Stories, branches and pull requests

AGENTS.md's "Story review workflow" applies; these are the Claude-specific details.

- Branch from an up-to-date `main` as `claude/<story-id>-<short-slug>` (for example `claude/FT-065-tenant-isolation`). Non-story work (docs, tooling) uses `claude/<short-slug>`.
- When starting a story, set it to `In progress` in the delivery log's status board **and** in the story's own tag line; set it to `Done` (with the session entry) in the same PR that delivers it. Update the board's totals line.
- Open the PR with `gh pr create --base main`, titled `FT-xxx: <story title>`, with what changed, why, and how it was verified (commands run and results). If `gh` isn't authenticated, push the branch and ask the user to run `! gh auth login`; don't fall back to merging.
- Never merge to `main`, push to `main`, or merge your own PR without the user's explicit go-ahead for that PR.
- When the user asks what's next or for the backlog, list each story with its status, priority and order from the status board, done stories included.

### Local environment quirks

- `:server:test` needs Docker Desktop running (Testcontainers 1.21.4; Docker Engine 29 rejects older Testcontainers).
- AVG Antivirus on this machine re-signs HTTPS traffic, which Java rejects (`PKIX path building failed`, unresolvable plugins, "Problems writing to Binary store"). Run Gradle with `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (README "Running tests"); don't commit machine-specific workarounds to the repo.
- Postgres (5432) and Keycloak (8081) on this machine belong to another project's containers and are shared; see README "Setting up the database" before running `docker compose up`, or move this stack's host ports (README "Using other host ports").
