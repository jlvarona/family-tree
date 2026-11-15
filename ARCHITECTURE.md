# Architecture

Family tree SaaS: a family builds one private, shared tree with photos, life stories and source
citations, and invites relatives to view or edit only the branches they belong to.

Full product concept, decisions log and data model: [docs/concept-architecture.md](docs/concept-architecture.md).
Desktop mockups (tree view, person page): [docs/mockups/](docs/mockups/).

## System overview

```mermaid
flowchart TB
  subgraph Clients["Clients: Compose Multiplatform (app/)"]
    A[androidApp] --- S
    I[iosApp] --- S
    D[desktopApp] --- S
    W["webApp (Wasm/JS), desktop-first"] --- S
    S["app/shared: UI, view models, API client, tree layout, offline cache"]
  end
  S --> C["core: domain model, branch + permission rules, DTOs (KMP, no I/O)"]
  Clients -- "HTTPS REST + WebSocket, JWT" --> B["server: Ktor modular monolith"]
  B --> C
  B --> PG[(PostgreSQL / RDS)]
  B --> S3[S3 + CloudFront]
  B --> AU["Auth: Keycloak OIDC"]
  B --> SES[Amazon SES]
  Clients -. presigned photo upload .-> S3
```

## Gradle modules

| Module | Targets | Owns | Must not |
| --- | --- | --- | --- |
| `:core` | Android, iOS, JVM, JS, Wasm | Domain types (Person, Union, Event, Source, Citation, Grant, Branch…), `@Serializable` API DTOs, branch resolution, permission evaluation, date parsing ("abt 1890"), name formatting (two surnames), validation, duplicate-match scoring | Do I/O, depend on Ktor server, Compose, or a database |
| `:server` | JVM | Ktor app: routing, auth, persistence (Postgres), media (S3), jobs, notifications | Contain domain rules that clients also need (put those in `:core`) |
| `:app:shared` | Android, iOS, JVM, JS, Wasm | Compose UI, navigation, view models, Ktor client, tree layout engine + canvas, i18n (ES/EN), offline cache | Talk to the DB or S3 except through the API (and presigned URLs) |
| `:app:androidApp`, `:app:desktopApp`, `:app:webApp`, `app/iosApp` | per platform | Thin entry points only | Hold feature code |

Package root everywhere: `com.moxietech.family_tree`.

## Core concepts

- **Tree** = tenant. Every row carries `tree_id`. Trees are private; no linking between families.
- **Union** model (GEDCOM-style): people connect through `Union` (two partners) + `UnionChild` (birth, adopted, step, foster), never direct parent edges.
- **Branch** = anchor person + direction (ancestors, descendants, "side" = ancestors + their descendants, whole tree) + depth + include partners. The same definition drives the tree views and the permissions.
- **Membership** links an account to its own `Person` in the tree. The member's **home branch** = their parents and the parents' descendants, derived automatically.
- **Permissions**
  - Edits inside the home branch go live immediately.
  - Edits elsewhere become a `ChangeProposal`, approved by the owner of that branch or the tree owner. A branch owner approves only inside the branch they own.
  - Owning a higher branch must be granted by the tree owner or that branch's owner.
  - People without accounts (deceased, children) can be edited by any contributor on their branch.
  - Living people are private unless a grant covers them; redaction is server-side.
  - Every write goes to `AuditLog` (before/after) for revert and the activity feed.
- **Sources & citations**: a `Source` is reused; a `Citation` ties one fact (person, event, union) to a source with page/detail, confidence 0–3 and optional scan.
- **Duplicate check** on person creation: fuzzy score on names, dates, places, parents; warn and offer merge.

## Server (Ktor modular monolith)

Feature packages under `com.moxietech.family_tree.server.<feature>`, each with `routes`, `service`, `repository`:
`identity`, `trees` (trees, memberships, invitations), `graph` (people, unions, events, places), `permissions`
(grant resolution, scope cache, proposal routing), `media`, `sources`, `collaboration` (proposals, audit, activity),
`notifications`. Later: `importexport` (GEDCOM), `billing` (Stripe).

- Persistence uses PostgreSQL 16, Flyway migrations, and Exposed. Ancestor and descendant queries use recursive CTEs over `union_record` and `union_child`. The planned `GrantScope` cache will store resolved person IDs per grant and rebuild when the graph changes. Row-level security is tracked as a future backstop.
- Repository integration tests start a disposable PostgreSQL 16 container and apply Flyway migrations. See the [README test commands](README.md#running-tests).
- Every request: authenticate (a Keycloak access token whose `aud` names the API, `OIDC_AUDIENCE`) → resolve the account by the token's verified issuer + subject, never by email → load membership → resolve scope → check with `:core` permission rules → execute → audit.
- Media: presigned S3 uploads/downloads, thumbnails in a worker, EXIF GPS stripped.
- Jobs: SQS (or Postgres job table) + worker in the same codebase.
- Real-time: WebSocket/SSE per tree for live edits.
- API: REST + OpenAPI; DTOs come from `:core`.

## Clients

- Compose Multiplatform everywhere; web via Wasm (JS fallback target exists).
- **Web is desktop-first**: full-width tree canvas, side panel for the selected person, mini-map, keyboard navigation. Mobile layouts are separate, not stretched.
- Tree layout engine in `:app:shared` commonMain: computes positions for any branch view; drawn on a Canvas with pan/zoom and lazy loading by generation.
- Offline cache on mobile: SQLDelight.
- Localisation: Spanish and English from day one.

## Infrastructure

AWS: ECS Fargate (server + worker), RDS PostgreSQL, S3 + CloudFront, SES, SQS; Terraform. Launch is free and
invitation-only; billing (per-tree subscription or paid storage) comes later.

## Open decisions

- Which citation source types to support first.
