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
Keycloak backend. The web client (Compose for Web, JS and Wasm) signs in with Keycloak
and covers the owner workflow and the full, ancestor and descendant tree views
([Running the web client](#running-the-web-client)). Android, iOS and desktop still show
the starter screen. See the delivery log for the exact cut line.

## Repository layout

| Module | What it is |
| --- | --- |
| [`core/`](./core/src) | Domain types, DTOs, validation, date parsing, name formatting — pure Kotlin, no I/O, shared by every target. Start in [`commonMain`](./core/src/commonMain/kotlin). |
| [`server/`](./server/src/main/kotlin) | The Ktor server: routing, Postgres persistence (Exposed + Flyway), Keycloak OIDC auth. |
| [`app/shared/`](./app/shared/src) | Compose Multiplatform UI, view models, API client — shared by Android/iOS/desktop/web. [`commonMain`](./app/shared/src/commonMain/kotlin) first; platform folders (`androidMain`, `iosMain`, `jvmMain`, `jsMain`, `wasmJsMain`) only for `expect`/`actual` code, and `webMain` (shared by JS and Wasm) for the browser wiring. |
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

### Accounts and identities (FT-003)

The server finds the caller's account by the access token's verified issuer (`iss`)
and subject (`sub`); Keycloak's subject is the user's ID. The first request from a
new identity creates its account. The email claim is stored as profile data and
refreshed when the provider reports a new address. It is not unique and never selects
or links an account, so a new identity using an old or current address gets a new,
empty account. Changing `OIDC_ISSUER` (for example a new realm URL) means every user
is a new identity, so treat it as a migration.

### Resolving legacy accounts

Accounts created before migration `V3__account_oidc_identity.sql` were keyed by email.
V3 keeps their IDs, trees, memberships, people and audit entries, but leaves them
**unbound**: no token reaches them, and signing in with the same email creates a
separate, empty account. An operator binds each one from an authoritative mapping.

1. **List them.** Connect with `psql` as the schema owner (the role Flyway migrates
   with, `family_tree` locally) and run:

   ```sql
   select * from unbound_legacy_account order by created_at;
   ```

   Each row shows the account ID, its legacy email and display name, and how many
   trees it owns, memberships it has, people it created and audit entries it wrote.
   Rows with no trees and no memberships can stay unbound.

2. **Build the mapping.** For each account to restore, find the provider user who
   held it and take that user's ID (Keycloak admin console → realm → Users → user →
   ID, or `kcadm.sh get users -r family-tree -q username=...`). The issuer is the
   exact `OIDC_ISSUER` value. **A matching email alone is not evidence**: the
   address may have been reassigned. Confirm the link with the account holder
   through a channel you already trust (for example the tree owner vouching for a
   relative, or the person confirming the trees they own), and record where the
   confirmation came from.

3. **Apply it in one transaction**, one call per account:

   ```sql
   begin;
   select resolve_legacy_account(
       '<account id>'::uuid,
       'http://localhost:8081/realms/family-tree',   -- OIDC_ISSUER
       '<keycloak user id>',                         -- sub
       'Confirmed by <who>, <how>, <date>; ticket <id>'
   );
   -- ...more calls...
   commit;
   ```

   Any failed call aborts the transaction and nothing is bound. The function
   refuses, without changes, when the account doesn't exist, is already bound to a
   different identity, or the identity already belongs to another account. Calling
   it again with the same account and identity is a no-op, so a mapping script can be
   re-run. Each binding is recorded in `account_identity_resolution` (identity,
   evidence, database user, time). A binding can't be changed afterwards; a trigger
   rejects direct updates to `oidc_issuer`/`oidc_subject`.

4. **If the person already signed in after the upgrade**, step 3 fails because their
   identity already has a new account. If that account is empty (owns no tree, has no
   membership, created no person, wrote no audit entry), add
   `p_replace_unused_account => true` as a fifth argument. The function deletes the
   empty account and binds the legacy one in the same transaction, recording the
   deleted ID as `replaced_account_id`. If the new account isn't empty, it still
   fails. Decide case by case which data to keep; the function never merges
   accounts.

5. **Check** that the account no longer appears in `unbound_legacy_account`, and
   that the person sees their trees after signing in again.

The server never calls `resolve_legacy_account`. Execute rights are revoked from
`public`, so only the schema owner, or a role it grants execute to, can run it.

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
| `MEDIA_S3_ENDPOINT` | `http://127.0.0.1:8333` (the local `s3` service). Set it to an empty value for Amazon S3 |
| `MEDIA_S3_PUBLIC_ENDPOINT` | `MEDIA_S3_ENDPOINT` (the host presigned URLs name; the browser must reach it) |
| `MEDIA_S3_BUCKET` / `MEDIA_S3_REGION` | `family-tree-media` / `us-east-1` |
| `MEDIA_S3_PATH_STYLE` | `true` when an endpoint is set, otherwise `false` |
| `MEDIA_S3_CREATE_BUCKET` | `false`. Local only: at startup, create the bucket and put `MEDIA_S3_CORS_ORIGINS` as its CORS rules |
| `MEDIA_S3_CORS_ORIGINS` | none (comma-separated browser origins, e.g. `http://localhost:3000`; `*` is refused) |
| `MEDIA_MAX_CONCURRENT_VERIFICATIONS` | `2` (image verifications decoding at once) |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | none. Read by the AWS SDK's default credential chain; in AWS the task role is used instead |

## Photo storage

FT-008 photo uploads never pass through the API. The owner's browser asks the API for a
presigned `PUT`, sends the file straight to the object store, then asks the API to
finalize it. The server reads the upload, checks it is exactly the declared size and a
complete JPEG or PNG (every pixel decoded), removes location and other hidden metadata
(Exif and GPS, XMP, IPTC, comments, JFIF/JFXX thumbnails, PNG text chunks; the Exif
orientation is kept), stores the cleaned file under a server-generated key and only then
marks it readable. Readers get a 5-minute presigned `GET`, only for images whose person
or citation they may see. Unverified uploads (`uploads/…`) are never presigned for
reading.

**Locally** the `s3` service in `docker-compose.yml` (SeaweedFS `weed mini`,
`ghcr.io/chrislusf/seaweedfs:4.54`, on `127.0.0.1:8333`) stands in for S3. It checks
signatures and keeps the bucket private. Choose any local-only credentials; they live
only in your shell. Set them **before** `docker compose up -d s3`: started without
them, the store has no credentials to check (it is still bound to loopback only), so
recreate it (`docker compose up -d --force-recreate s3`) once they are set.

```powershell
# PowerShell (bash: export VAR=value)
$env:AWS_ACCESS_KEY_ID = "local-dev-only"
$env:AWS_SECRET_ACCESS_KEY = "<any random string>"
docker compose up -d s3          # creates the private bucket family-tree-media
$env:MEDIA_S3_CREATE_BUCKET = "true"
$env:MEDIA_S3_CORS_ORIGINS = "http://localhost:3000"
.\gradlew.bat :server:run        # same shell: puts the bucket's CORS rules, then serves
```

**Browser CORS.** The browser `PUT`s and `GET`s across origins (page on
`http://localhost:3000`, store on `http://127.0.0.1:8333`), so the bucket needs CORS
rules. The server bootstraps them through the S3 API (`PutBucketCors`) when
`MEDIA_S3_CREATE_BUCKET=true`: origins from `MEDIA_S3_CORS_ORIGINS`, methods `PUT` and
`GET`, header `content-type`, `ETag` exposed, 600 s max age. The API itself still has no
CORS. If the page shows "The photo couldn't be sent to photo storage", check the
browser console for a CORS error first.

**Deployment (not set up by this story).** Use a private S3 bucket with Block Public
Access, default encryption, the same CORS rules for the production origin, and a
lifecycle rule expiring `uploads/` after one day (it removes uploads that were never
finalized or whose cleanup failed). The task role needs `s3:PutObject`,
`s3:GetObject` and `s3:DeleteObject` on the bucket's objects. Set `MEDIA_S3_ENDPOINT`
empty, `MEDIA_S3_BUCKET` and `MEDIA_S3_REGION`; never set `MEDIA_S3_CREATE_BUCKET`.

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
- **Web app:** see [Running the web client](#running-the-web-client) (needs the server
  and Keycloak running)
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- **Android app:** `./gradlew :app:androidApp:assembleDebug` (installs via Android Studio's run button, or `adb install` the built APK)
- **iOS app:** open [`app/iosApp`](./app/iosApp) in Xcode and run from there.

Or use the run configurations in your IDE's toolbar instead of the CLI for any of these.

## Running the web client

The web client (FT-005) is Compose for Web on both web targets. Shared state, API client,
sign-in and tree layout live in `app/shared`; `app/webApp` only mounts
`FamilyTreeWebApp()`.

```bash
# 1. Keycloak with the repo realm (or your own; see "Setting up the database")
docker compose up -d keycloak

# 2. The API on :8080
./gradlew :server:run

# 3. The web client on http://localhost:3000 (pick one target)
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
./gradlew :app:webApp:jsBrowserDevelopmentRun
```

Open http://localhost:3000 and sign in as `dev@example.com` / `devpassword`.

**Dev server and API routing.** [`app/webApp/webpack.config.d/devServer.js`](./app/webApp/webpack.config.d/devServer.js)
serves the app on port 3000 (Kotlin's default, 8080, is the API's) and proxies `/api`
to `http://localhost:8080`. The browser calls the API on its own origin, so the server
needs no CORS settings. **Production** must do the same: serve the static bundle and
route `/api/*` to the Ktor server on one origin (for example CloudFront or ALB path
routing). Otherwise set `apiBaseUrl` and add CORS to the server, which FT-005 doesn't do.

**Configuration** is read at startup from `config.json` next to `index.html`
([`app/webApp/src/webMain/resources/config.json`](./app/webApp/src/webMain/resources/config.json)),
so each deployment can supply its own file without a rebuild. Nothing in it is secret.

| Key | Default | Meaning |
| --- | --- | --- |
| `issuer` | `http://localhost:8081/realms/family-tree` | The realm URL as the browser reaches it. It must equal the API's `OIDC_ISSUER`, because Keycloak puts the URL the browser used into the token's `iss`. |
| `clientId` | `family-tree-web` | The realm's public browser client. |
| `apiBaseUrl` | `""` | Empty means the page's origin (the proxy above). |

If your Keycloak runs elsewhere (for example on another port because 8081 is taken), change
`issuer` here and `OIDC_ISSUER` for the server together.
Use HTTPS for the app, Keycloak and API in production. Loopback URLs can use HTTP for local development.

**Sign-in.** The client uses Keycloak's authorization code flow with PKCE (S256) and the
existing `family-tree-web` client and its API audience mapper (FT-067); the realm isn't
changed.
- Web Crypto generates `state`, `nonce` and the verifier and computes the S256 challenge.
  The client keeps the one-time request in `sessionStorage` and removes it when the
  fragment callback arrives. It checks for a single matching `state` before exchanging
  the code, removes callback parameters from the address bar and checks the returned ID
  token's issuer, audience, expiry and nonce before starting a session.
- Tokens are held in memory only; nothing is put in `localStorage` and nothing is logged.
  After a reload, a `sessionStorage` flag tells the app to ask Keycloak again with
  `prompt=none`, which signs the tab back in while the Keycloak session lasts and returns
  to the same page.
- The access token is refreshed before it expires and once after an API `401`. If the
  refresh token is rejected, or Keycloak's session has ended at reload, the app drops all
  tree data and shows "Your session has expired".
- **Sign out** clears the tab, then opens Keycloak's end-session endpoint with
  `id_token_hint`. The realm lists no post-logout redirect URI for `family-tree-web`,
  so Keycloak shows its own "logged out" page; open http://localhost:3000 again from
  there.
- Another origin needs to be added to the client's redirect URIs and web origins by a
  realm administrator. The repo realm allows `http://localhost:3000/*` and
  `http://localhost:8080/*`.

**Language.** The UI follows the browser's preferred language: Spanish for `es`,
English otherwise. To check the Spanish strings, put Spanish first in the browser's
language settings and reload.

**Manual check (FT-005 acceptance).** With a fresh tree:
1. Sign in, create a tree and add three people. Open *Link child*, choose the parents,
   choose an existing union or create one with the appropriate type, then select the child.
2. Reload. The tab signs back in and the full tree shows the parents joined, with the
   child below.
3. Select the child, choose *Center tree here*, then *Ancestors*; select a parent and
   switch to *Descendants*. Reload in each view and confirm it comes back.
4. Select a person and *Open page*; reload the page, then *Back to tree*.
5. Photos (FT-008, needs [Photo storage](#photo-storage)): on a person page choose
   *Add photo*, pick a JPEG or PNG, and check it appears (also after reload), then
   *Change photo* and *Remove photo*.
6. Empty and failure states: a new tree shows the empty state; stopping the server shows
   the "can't be reached" message with *Try again*.
7. Expiry: end the session in the Keycloak admin console (Users → dev user → Sessions →
   Sign out) and wait for the next refresh (up to 15 minutes), or reload. The app returns
   to sign-in with the expired message and shows no tree data.

## Running tests

```bash
./gradlew :core:jvmTest                        # :core domain/validation/format logic
./gradlew :server:test                          # server routing tests + Testcontainers Postgres integration test
./gradlew :app:shared:jvmTest                   # shared client logic on the JVM: sign-in, API client, routes, layout, controller
./gradlew :app:shared:wasmJsTest                # the same common tests on Wasm
./gradlew :app:shared:jsTest                     # the same common tests on JS
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
`PlaceAuditTest` (place creation and its audit entry commit or roll back together, FT-069),
`OidcAccessTokenTest` (token audience, type and expiry through the production
verifier with a test JWKS, FT-067), `AccountIdentityTest` (accounts keyed by issuer and
subject: parallel first logins, email changes and reused emails, FT-003),
`LegacyAccountBackfillTest` (V3 on pre-FT-003 data, listing and resolving legacy
accounts, FT-003), `TreeScopedMigrationTest` (V2 migration preflight and backfill) and
`AuthenticatedAccessTest` (FT-072: tenant isolation, living-person privacy, membership
and owner-only writes over HTTP, through the production module and verifier with
test-signed access tokens for two owners, an active non-owner and an invitee) and
`PersonMergeTest` (FT-007: duplicate-person merges over HTTP, including authorization,
cross-tree IDs, versions, graph conflicts, audit rollback and races with event and
citation writers), `MediaMigrationTest` (V6 on existing data and its preflight, FT-008)
and `MediaRoutesTest` (FT-008: presign, direct upload, finalize, retry, rejection,
privacy-scoped downloads, citation scans, merge photo choice and lock order).

`MediaRoutesTest` also starts a disposable `ghcr.io/chrislusf/seaweedfs:4.54`
(`weed mini`) on a random loopback port with random credentials, so presigned
signatures, private-bucket denial and the CORS preflight are checked against a real
S3-compatible service. Try another image with `-PobjectStoreImage=<image:tag>`.
`ImageSanitizerTest` needs no containers.

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

### Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request
targeting `main`, with no path filters. Its single job, the **`Core and server tests`**
check, runs on `ubuntu-24.04` (Docker Engine for Testcontainers) with Azul JDK 21, the
daemon JVM in `gradle/gradle-daemon-jvm.properties`:

```bash
bash ./gradlew --no-daemon --stacktrace :core:jvmTest :server:test
```

The workflow's token is read-only (`contents: read`), checkout doesn't keep
credentials, and the actions are GitHub's own (`actions/checkout`,
`actions/setup-java`) pinned to full commit SHAs. The live Keycloak test stays opt-in
and is skipped in CI. Every database is a disposable PostgreSQL 16 container and every
token is signed by a key generated for that test run.

The [main ruleset](https://github.com/jlvarona/family-tree/rules/27332094) requires
**`Core and server tests`** from GitHub Actions before merging.

## Contributing

Open a separate pull request into `main` for each completed story. Include relevant
documentation updates and test results, and leave the PR open for review before
merging. See the [story review workflow](AGENTS.md#story-review-workflow).

## Logs

- **Server logs** go to stdout of whatever shell ran `./gradlew :server:run` — there's
  no separate log file. Format and level are configured in
  [`server/src/main/resources/logback.xml`](./server/src/main/resources/logback.xml);
  it defaults to `INFO`: startup, Flyway, and one status line per request (method, path,
  status, duration), without bodies, tokens, or SQL. For local debugging, opt in for a
  single process with `FAMILY_TREE_LOG_LEVEL=DEBUG` (or `TRACE`) in that shell, e.g.
  `$env:FAMILY_TREE_LOG_LEVEL = "DEBUG"` in PowerShell. Verbose levels log SQL with bind
  values (names, dates, places, notes) and HTTP traces, so never set them in shared or
  deployed environments.
- **Flyway migration output** reports applied migrations and the final status in the
  same server stdout during startup.
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
