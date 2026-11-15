# Family Tree

A multi-tenant family-tree SaaS: a family builds one private, shared tree — people,
photos, life stories, source citations — and invites relatives to view or edit only the
branches they belong to. "Any person can be the focal point" (ancestors, descendants,
maternal/paternal side, re-centre on anyone) and "stories first" (a rich bio, a life
timeline, a photo gallery, not just names and dates) are the two things meant to set
this apart from a plain genealogy chart tool.

Kotlin Multiplatform end to end: one `:core` module of domain rules, validation and DTOs
shared by the Ktor server and every Compose Multiplatform client (Android, iOS, desktop,
web). See [ARCHITECTURE.md](ARCHITECTURE.md) for the module map and core concepts,
[docs/concept-architecture.md](docs/concept-architecture.md) for full product scope and
the decisions log, and [docs/delivery-log.md](docs/delivery-log.md) for what's actually
been built so far and the current database design.

**Status:** Phase 1 ("Foundations") is underway — accounts, trees, people, unions,
events, sources and the ancestor/descendant graph queries exist behind a real Postgres +
Keycloak backend. No Compose client screens yet; see the delivery log for the exact
cut line.

## Repository layout

| Module | What it is |
| --- | --- |
| [`core/`](./core/src) | Domain types, DTOs, validation, date parsing, name formatting — pure Kotlin, no I/O, shared by every target. Start in [`commonMain`](./core/src/commonMain/kotlin). |
| [`server/`](./server/src/main/kotlin) | The Ktor server: routing, Postgres persistence (Exposed + Flyway), Keycloak OIDC auth. |
| [`app/shared/`](./app/shared/src) | Compose Multiplatform UI, view models, API client — shared by Android/iOS/desktop/web. [`commonMain`](./app/shared/src/commonMain/kotlin) first; platform folders (`androidMain`, `iosMain`, `jvmMain`, `wasmJsMain`) only for `expect`/`actual` code. |
| [`app/androidApp/`](./app/androidApp), [`app/desktopApp/`](./app/desktopApp), [`app/webApp/`](./app/webApp), [`app/iosApp/`](./app/iosApp) | Thin per-platform entry points. |
| [`docs/`](./docs) | Product concept, decisions log, delivery log, desktop mockups. |
| [`ops/keycloak/`](./ops/keycloak) | The seeded Keycloak realm used by local dev. |

## Prerequisites

- **JDK 21** — Gradle's toolchain auto-provisions it (via `gradle/gradle-daemon-jvm.properties`); you don't need to install it yourself.
- **Docker Desktop** — runs the local Postgres and Keycloak used by the server and its tests.
- **Android Studio** (only for the Android app) and an Android SDK; point `local.properties` (git-ignored) at it.
- **Xcode on macOS** (only for the iOS app).
- On Windows, use `gradlew.bat`, or `./gradlew` from Git Bash — all commands below are written for Git Bash/macOS/Linux shells.

## Building

```bash
# One module at a time is faster while iterating (first run is slow; build/config cache speeds up the rest):
./gradlew :core:jvmTest          # :core compiles for every target as a side effect of this
./gradlew :server:compileKotlin

# The full cross-target build (compiles Android/iOS/JS/Wasm too — needs the Android SDK and, for iOS targets, macOS):
./gradlew build
```

## Setting up the database (and Keycloak)

Local dev runs a real Postgres and a real Keycloak — never a bypass login or an
in-memory stand-in, so dev exercises the same code paths as production. There are two
ways to get the Postgres side; pick whichever matches your machine.

**Option A — bundled container (default, nothing else running on 5432):**

```bash
# Start Postgres 16 + Keycloak 26 in the background
docker compose up -d postgres keycloak

# Check they're healthy / see what they're doing
docker compose ps
docker logs -f family-tree-postgres
docker logs -f family-tree-keycloak

# Tear down (add -v to also wipe the Postgres volume for a totally clean slate)
docker compose down
docker compose down -v
```

**Option B — reuse a Postgres container another project already has running** (same
host, same port 5432 — starting `docker compose up postgres` here would just fail to
bind the port). Create a dedicated role and database for this project inside it:

```bash
# Replace <container> and <admin-user>/<admin-db> with the other project's values
docker exec <container> psql -U <admin-user> -d <admin-db> -c \
  "CREATE ROLE family_tree LOGIN PASSWORD 'family_tree';"
docker exec <container> psql -U <admin-user> -d <admin-db> -c \
  "CREATE DATABASE family_tree OWNER family_tree;"

# Verify
docker exec <container> psql -U family_tree -d family_tree -c "\conninfo"
```

With a role/database both named `family_tree`, the server's defaults (below) work with
no environment variables set, as long as the shared instance is reachable on
`localhost:5432`. If it's on a different host/port, set `DB_URL` accordingly.

Either way, Keycloak still needs to come from somewhere — either `docker compose up -d
keycloak` here, or another running instance with its own realm, in which case point
`OIDC_ISSUER`/`OIDC_JWKS_URI` (below) at it instead of the one seeded in this repo.

There is no separate "run migrations" command — the server runs every pending Flyway
migration from [`server/src/main/resources/db/migration`](./server/src/main/resources/db/migration)
automatically on startup (`DatabaseConfig.migrate(...)` in `Application.kt`), against
whatever Postgres `DB_URL`/`DB_USER`/`DB_PASSWORD` point at (see below). It's safe to
restart the server repeatedly — already-applied migrations are a no-op. **Never edit an
applied migration file** — add a new `V<n>__description.sql` instead.

Keycloak comes up with a seeded `family-tree` realm
([`ops/keycloak/realm-export.json`](./ops/keycloak/realm-export.json)):

- Admin console: http://localhost:8081 (`admin` / `admin`)
- A public client `family-tree-web` for the browser, desktop and mobile apps, and a
  bearer-only resource-server client `family-tree-server` that stands for the API
- An audience mapper on `family-tree-web` (`family-tree-api-audience`) that adds
  `family-tree-server` to the `aud` of its access tokens (not its ID tokens)
- A seeded dev user: `dev@example.com` / `devpassword`

The server accepts only access tokens whose `aud` contains `OIDC_AUDIENCE` (default
`family-tree-server`), signed by a key from `OIDC_JWKS_URI`, issued by `OIDC_ISSUER`,
and not expired. ID tokens, refresh tokens, tokens without the API audience and tokens
without an expiry get `401`.

### Updating an existing local realm (FT-067)

`--import-realm` skips a realm that already exists, so a Keycloak started before
FT-067 doesn't have the audience mapper, and the API rejects its tokens with `401`.
Either recreate the container, or add the mapper in place.

- **Recreate** (the bundled `family-tree-keycloak` container keeps its data inside the
  container, so this drops any users or changes made in the admin console):

  ```bash
  docker compose up -d --force-recreate keycloak
  ```

- **Add the mapper in place** with the admin CLI inside the Keycloak container
  (replace `family-tree-keycloak` if your container has another name; in Git Bash on
  Windows, run `export MSYS_NO_PATHCONV=1` first so `/opt/...` isn't rewritten):

  ```bash
  KC=family-tree-keycloak
  docker exec $KC /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:8080 --realm master --user admin --password admin
  WEB_ID=$(docker exec $KC /opt/keycloak/bin/kcadm.sh get clients -r family-tree \
    -q clientId=family-tree-web --fields id --format csv --noquotes)
  docker exec $KC /opt/keycloak/bin/kcadm.sh create clients/$WEB_ID/protocol-mappers/models \
    -r family-tree -s name=family-tree-api-audience -s protocol=openid-connect \
    -s protocolMapper=oidc-audience-mapper \
    -s 'config."included.client.audience"=family-tree-server' \
    -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false' \
    -s 'config."introspection.token.claim"=true'
  ```

  In the admin console the same change is: realm `family-tree` → Clients →
  `family-tree-web` → Client scopes → `family-tree-web-dedicated` → Add mapper → By
  configuration → Audience; name `family-tree-api-audience`, included client audience
  `family-tree-server`, "Add to access token" on, "Add to ID token" off.

Sign in again afterwards (tokens issued before the change don't carry the audience) and
check the new access token's `aud` includes `family-tree-server`.

Other Keycloak installations need the same mapper on whichever client the apps sign in
with; if the API's client ID there isn't `family-tree-server`, set `OIDC_AUDIENCE` to it.

Server configuration is all environment variables, defaulting to the values above —
nothing to set for local dev, only for pointing at something else:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/family_tree` |
| `DB_USER` | `family_tree` |
| `DB_PASSWORD` | `family_tree` |
| `OIDC_ISSUER` | `http://localhost:8081/realms/family-tree` |
| `OIDC_JWKS_URI` | `${OIDC_ISSUER}/protocol/openid-connect/certs` |
| `OIDC_AUDIENCE` | `family-tree-server` (the value an access token's `aud` must contain) |

## Running a local copy end to end

```bash
# 1. Bring up Postgres + Keycloak
docker compose up -d postgres keycloak

# 2. Start the server (migrates the DB on boot, then listens on :8080)
./gradlew :server:run

# 3. In another shell, get a real access token for the seeded dev user
TOKEN=$(curl -s http://localhost:8081/realms/family-tree/protocol/openid-connect/token \
  -d "grant_type=password" -d "client_id=family-tree-web" \
  -d "username=dev@example.com" -d "password=devpassword" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

# 4. Call the API
curl http://localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8080/api/v1/trees -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"name":"My Family"}'
```

See [docs/delivery-log.md](docs/delivery-log.md) for the full route list.

## Running the clients

- **Server:** `./gradlew :server:run` (see above — needs Postgres + Keycloak running first)
- **Desktop app:**
    - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
    - Standard run: `./gradlew :app:desktopApp:run`
- **Web app:**
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- **Android app:** `./gradlew :app:androidApp:assembleDebug` (installs via Android Studio's run button, or `adb install` the built APK)
- **iOS app:** open [`app/iosApp`](./app/iosApp) in Xcode and run from there.

Or use the run configurations in your IDE's toolbar instead of the CLI for any of these.

## Running tests

```bash
./gradlew :core:jvmTest                        # :core domain/validation/format logic
./gradlew :server:test                          # server routing tests + Testcontainers Postgres integration test
./gradlew :app:shared:jvmTest                   # desktop-target shared code
./gradlew :app:shared:wasmJsTest                # web (Wasm) shared code
./gradlew :app:shared:jsTest                     # web (JS) shared code
./gradlew :app:shared:testAndroidHostTest       # Android shared code
./gradlew :app:shared:iosSimulatorArm64Test     # iOS shared code (macOS only)

./gradlew check                                 # everything above, the intended full gate (slow; prefer the targeted commands while iterating)
```

`:server:test` needs a running Docker engine. Testcontainers starts a disposable
`postgres:16` container and applies the Flyway migrations. A Docker discovery
failure fails the test. The graph integration test was verified on Windows with
Docker Desktop (Engine 29.7.2) and Testcontainers 1.21.4. The Postgres-backed suites
are `GraphIntegrationTest`, `TenantIsolationTest` (cross-tree access, FT-065),
`MembershipAccessTest` (active membership and owner-only writes, FT-068),
`LivingPrivacyTest` (living-person privacy across responses, FT-066),
`OidcAccessTokenTest` (token audience, type and expiry through the production
verifier with a test JWKS, FT-067) and `TreeScopedMigrationTest` (V2 migration
preflight and backfill).

`KeycloakBrowserTokenTest` is opt-in. It starts a disposable Keycloak 26 and Postgres
on random ports (nothing already running is touched), imports
`ops/keycloak/realm-export.json`, signs the dev user in to `family-tree-web` with
authorization code + PKCE, and sends the tokens to the API: the access token gets
`200`, the ID token and an `admin-cli` token without the API audience get `401`.

```bash
./gradlew :server:test --tests "*KeycloakBrowserTokenTest" -PkeycloakIt=true
```

**Gradle fails with `PKIX path building failed` or can't resolve plugins?** HTTPS
scanning in some antivirus products (AVG, Avast and others) re-signs TLS traffic with
a root certificate that Windows trusts but Java doesn't. Tell Java to use the Windows
certificate store, then refresh dependencies once to clear the failed downloads:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\gradlew.bat --stop
.\gradlew.bat :server:test --refresh-dependencies
```

Set `JAVA_TOOL_OPTIONS` permanently in your user environment variables if you don't
want to set it in each shell.

Run the graph integration test on its own:

```powershell
.\gradlew.bat :server:test --tests "*GraphIntegrationTest"
```

```bash
bash ./gradlew :server:test --tests "*GraphIntegrationTest"
```

Per-module HTML test reports: `<module>/build/reports/tests/test/index.html`. Raw JUnit
XML: `<module>/build/test-results/test/*.xml`.

## Contributing

Open a separate pull request into `main` for each completed story. Include relevant
documentation updates and test results, and leave the PR open for review before
merging. See the [story review workflow](AGENTS.md#story-review-workflow).

## Logs

- **Server logs** go to stdout of whatever shell ran `./gradlew :server:run` — there's
  no separate log file. Format and level are configured in
  [`server/src/main/resources/logback.xml`](./server/src/main/resources/logback.xml);
  it currently defaults to `TRACE`, which is very verbose (every SQL statement, every
  routing decision, full HTTP wire traces) — useful when debugging a specific request,
  noisy otherwise. Lower `<root level="trace">` to `info` there for quieter day-to-day
  output.
- **Flyway migration output** (each SQL statement run, and the final "Successfully
  applied N migrations" line) appears inline in the same server stdout, right at startup.
- **Postgres / Keycloak container logs:** `docker logs family-tree-postgres` /
  `docker logs family-tree-keycloak` (`-f` to follow). `docker compose logs -f` follows
  both at once.
- **Test output:** Gradle prints failures to the console; full logs (including anything
  written via logback during the test run) are in
  `<module>/build/test-results/test/TEST-*.xml` and the HTML report linked above.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web). If you face any issues, please report them
on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
