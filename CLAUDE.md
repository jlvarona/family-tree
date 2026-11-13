# CLAUDE.md

@AGENTS.md

@ARCHITECTURE.md

## Claude Code notes

- This file imports AGENTS.md (shared agent rules) and ARCHITECTURE.md; keep project rules there so every agent sees them, and keep this file for Claude-specific notes.
- Development machine is Windows: use `gradlew.bat` (or `./gradlew` from Git Bash). Paths in docs use forward slashes.
- Gradle builds are slow on first run (configuration cache and build cache are enabled); prefer running a single module's tests (`:core:jvmTest`, `:server:test`) over `check` while iterating.
- Before large changes, outline the plan and the files you'll touch; update `docs/concept-architecture.md` (decisions table) when the user settles an open question.
- The original concept doc lives online at https://claude.ai/artifact/UEGgKDMKLry47RfF9imFtZ; `docs/concept-architecture.md` is the in-repo source of truth from now on.
