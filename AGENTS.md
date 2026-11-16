# AGENTS.md

Instructions for AI coding agents (Claude Code, Codex, Junie, etc.) working in this repository.

## Read first

1. [ARCHITECTURE.md](ARCHITECTURE.md) — modules, core concepts, permission rules.
2. [docs/concept-architecture.md](docs/concept-architecture.md) — product scope, data model, decisions log, roadmap.
3. [docs/mockups/](docs/mockups/) — desktop tree view and person page (layout reference; they render only on the design canvas).
4. [docs/delivery-log.md](docs/delivery-log.md) — what's actually been built so far, session by session, and the current DB design. Add an entry here at the end of a session that changes what's built; don't let it drift from the code.

Before committing a change, review all affected documentation. Update the README for setup, commands, or
test requirements, the architecture and decisions docs for design changes, and the delivery log for
shipped work, verification, schema changes, or backlog status. Include the relevant documentation
updates in the commit that changes the behavior.

## Project status

The Phase 1 server foundation and shared domain model are implemented. The web client (FT-005)
signs in with Keycloak and covers the owner workflow, tree views and the person page (FT-006). Android, iOS and desktop still use
the starter UI. See [the delivery log](docs/delivery-log.md) for the current backlog and verification.

Current roadmap phase: **1. Foundations** — accounts, trees, people, unions, events, sources; full-tree and
ancestor/descendant views on web; photo upload.

## Stack

- Kotlin 2.4, Gradle (Kotlin DSL) with version catalog `gradle/libs.versions.toml`. Add every dependency there, never inline versions.
- Server: Ktor 3 (Netty), logback, kotlinx.serialization, PostgreSQL, Exposed, Flyway, and Keycloak OIDC.
  Integration tests use Testcontainers. AWS SDK integrations are planned for S3, SES, and SQS.
- Clients: Compose Multiplatform 1.12, Material 3, Ktor client (JS engine on web), kotlinx.serialization. Planned: SQLDelight (mobile cache), Coil (images).
- Targets: Android, iOS (arm64 + simulator), JVM desktop, JS + Wasm web, JVM server.

## Commands

On Windows use `gradlew.bat` instead of `./gradlew`.

| Task | Command |
| --- | --- |
| Server | `./gradlew :server:run` |
| Desktop app | `./gradlew :app:desktopApp:run` (hot reload: `:app:desktopApp:hotRun --auto`) |
| Web (Wasm) | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` |
| Android | `./gradlew :app:androidApp:assembleDebug` |
| iOS | open `app/iosApp` in Xcode |
| Server tests | `./gradlew :server:test` |
| Core tests (JVM) | `./gradlew :core:jvmTest` |
| Shared tests | `./gradlew :app:shared:jvmTest` (also `:app:shared:wasmJsTest`, `:app:shared:testAndroidHostTest`) |
| All checks | `./gradlew check` |

Run the relevant tests before saying a change is done.

## Where code goes

- Domain rules shared by server and clients (branch resolution, permission checks, date parsing, name formatting, duplicate scoring, validation) → `:core` commonMain, pure Kotlin, no I/O, fully unit tested.
- API DTOs → `:core` (`com.moxietech.family_tree.api`), `@Serializable`.
- Server features → `:server`, package `com.moxietech.family_tree.server.<feature>` with `routes` / `service` / `repository`.
- UI, view models, API client, tree layout → `:app:shared` commonMain. Platform code only in the matching `*Main` source set via `expect`/`actual`.
- Platform app modules stay thin entry points.

## Non-negotiable rules

- **Tenant isolation**: every table has `tree_id`; every query filters by it. Never return data across trees.
- **Permissions are enforced on the server.** Client-side checks are for UI only. Use the `:core` permission functions so server and UI agree.
- **Living-person privacy**: living people outside the caller's grants are redacted server-side ("Private", no dates/photos/bio).
- **Every write is audited** (actor, entity, before, after).
- **Out-of-branch edits become ChangeProposals**, never direct writes.
- **Union model**: relationships go through `Union` / `UnionChild`; do not add direct parent/child columns on Person.
- **Names**: store `given_names`, `surname_paternal`, `surname_maternal`; format via `:core`, never by string concatenation in UI.
- **i18n**: no hard-coded user-facing strings; Spanish and English resources from the start.
- **Media**: clients upload to S3 via presigned URLs; never stream file bytes through the API.
- **Migrations**: schema changes only through new Flyway migration files; never edit an applied migration.
- No secrets in the repo; use env vars / AWS Secrets Manager. `local.properties` is git-ignored.

## Story review workflow

- Start each story on a dedicated branch based on `main`. Include the story ID in
  the branch name and pull request title when one exists.
- Commit the implementation, tests, and affected documentation on the story branch.
  Run the relevant checks, push the branch, and open one pull request per completed
  story targeting `main`. Include what changed, why, and how it was verified.
- Share the pull request link and leave it open for review. Merge into `main` only
  after review and explicit authorization.

## Conventions

- Kotlin official code style (`kotlin.code.style=official`).
- Coroutines for all I/O; no blocking calls on request threads.
- Prefer small, focused files; one feature per package.
- Tests: `kotlin.test` in common code; Ktor `testApplication` for routes; Testcontainers Postgres for repositories.
- Commit messages: imperative, short subject, body explaining why.

## When unsure

Check the decisions table in `docs/concept-architecture.md`. If a decision is not recorded there, ask the user
rather than guessing, and record the answer in that table.
