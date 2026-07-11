---
name: release
description: Release Movement Logger to GitHub + Google Play (tag-push pipeline straight to the production track, secrets, Play listing source of truth, first-release caveats)
---

## Release pipeline

Releases are driven from a single source: push a `vX.Y.Z` git tag and the GitHub Actions workflow at `.github/workflows/release.yml` builds the signed AAB + APK, runs `scripts/publish_to_play.py` to upload everything (bundle, listing texts, screenshots, 512×512 icon, video URL, contact email, release notes) **straight to the Play Store production track** (`--release-status completed` — 100 % rollout as soon as Google's review approves; zdavatz's standing rule: always release straight to production, no internal-track staging) via the Android Publisher v3 API directly, and creates a GitHub release with the binaries attached. No local commit-and-bump dance needed.

```sh
git tag v0.0.6
git push origin v0.0.6
# Watch the workflow run; binaries land on GitHub + Play within ~5 min.
```

The workflow derives both `versionName` and `versionCode` from the tag:

- `versionName` = the tag minus the `v` prefix (`v0.0.6` → `"0.0.6"`).
- `versionCode` = `major * 10000 + minor * 100 + patch` (`0.0.6` → `6`, `0.1.0` → `100`, `1.0.0` → `10000`). Monotonic for any sane semver bump.

The `appVersionName` / `appVersionCode` Gradle properties in `app/build.gradle.kts` honor these, with sensible fallback defaults so local `assembleRelease` builds still work without CLI args.

### Required GitHub secrets

Add via Settings → Secrets and variables → Actions:

| Secret name                    | Value                                                                                          |
| ------------------------------ | ---------------------------------------------------------------------------------------------- |
| `RELEASE_KEYSTORE_BASE64`      | `base64 -w0 keystore/movement_logger_upload.keystore` output (single line, no newlines)                       |
| `STORE_PASSWORD`               | the `STORE_PASSWORD` value from local `signing.properties`                                      |
| `KEY_ALIAS`                    | the `KEY_ALIAS` value                                                                          |
| `KEY_PASSWORD`                 | the `KEY_PASSWORD` value                                                                       |
| `PLAY_SERVICE_ACCOUNT_JSON`    | full JSON contents of the Play Console service-account key (paste the file verbatim)            |

Service-account JSON comes from Google Cloud Console (Service Accounts → Create → download JSON key) plus a one-time grant in Play Console → Nutzer und Berechtigungen → invite the SA email and give Admin / all-permissions on Movement Logger. The Android Publisher API must be enabled in the GCP project (one-time, click-through prompt the first time you use it).

### Local development

`signing.properties` + `keystore/movement_logger_upload.keystore` at the repo root work the same as before for local signed builds (`./gradlew bundleRelease assembleRelease`). The Play Publisher plugin's tasks are disabled via `play { enabled.set(false) }`, so day-to-day builds are unaffected by the missing service-account JSON. Drop `play-service-account.json` at the repo root if you want to run `scripts/publish_to_play.py` locally — it's gitignored.

### Production is the default — no promotion step

Every tag push publishes to the **production** track with status `completed`;
Google auto-sends it for review and it rolls out to 100 % on approval (hours
to a couple of days). There is no internal-track staging step anymore — the
old two-stage flow (internal upload + manual promote) is retired.

Manual publish (fallback only — e.g. re-pushing a version after a Play-side
failure without re-tagging):

```sh
./gradlew bundleRelease -PappVersionName=0.0.X -PappVersionCode=X --rerun-tasks
python3 scripts/publish_to_play.py \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --version-name 0.0.X \
  --track production \
  --release-status completed
```

Needs `signing.properties` + keystore + `play-service-account.json` locally —
none of which live on the dev machine by default (CI has them as secrets), so
in practice: fix the problem, bump the patch version, push a new tag.

While the app was still in Play Console "draft" state (no production release
ever approved), the API only accepted `--release-status draft`; both the
script and the workflow auto-fall back to draft in that case and the release
then needs a manual **Send for review** click in Play Console. That one-time
gate has been passed (v0.0.48 committed with status=completed), so it's
history unless the app is ever re-created.

### Play listing source of truth

`app/src/main/play/` holds the listing metadata that `scripts/publish_to_play.py` uploads on each release:

- `default-language.txt` — `de-DE` (matches Play Console's default locale, which was set when the app was created with a German UI)
- `listings/de-DE/title.txt`, `short-description.txt`, `full-description.txt`
- `listings/de-DE/video-url.txt` — YouTube watch URL (one per locale; Play rejects direct uploads)
- `listings/de-DE/graphics/phone-screenshots/*.png` — synced from `/screenshots/`; min 2, max 8, 16:9 or 9:16 (or close to it, each side max 2.3× the other)
- `listings/de-DE/graphics/icon/icon.png` — 512×512 RGB (no alpha; Play rejects alpha channels on icons)
- `release-notes/de-DE/default.txt` — "what's new" string for the upcoming release

Edit these files in the same commit that bumps the version, and they ride along on the next tag push.

### Why we replaced Gradle Play Publisher with a direct-API script

Three independent reasons:

1. **Deprecated IAP endpoint.** GPP's umbrella `publishReleaseApps` chains in `publishReleaseProducts` + `publishReleaseSubscriptions`, which hit the legacy `/applications/.../inappproducts` v3 endpoint. Google flagged it with `PERMISSION_DENIED: "Please migrate to the new publishing API."` for apps that have never had in-app products configured. Same for the `bootstrapListing` task — both query IAPs even when none exist.
2. **No `changesNotSentForReview` in 3.x.** GPP 3.13's `play { }` extension lacks `changesNotSentForReview` (landed in 4.0, which requires Gradle 9). We initially needed it to bypass the review gate; turned out Google now rejects `changesNotSentForReview=true` with `INVALID_ARGUMENT: "Changes are sent for review automatically"` for this app, so we don't need it after all — but discovering that took several failed runs.
3. **Aggressive Gradle UP-TO-DATE caching.** GPP's `publishReleaseListing` decides what's been uploaded based on local file mtimes, not on actual Play state. If you re-run the workflow after a partial failure, GPP says "UP-TO-DATE" and skips the uploads even though Play has nothing. Forcing `--rerun-tasks` works but defeats incremental builds elsewhere.

`scripts/publish_to_play.py` sidesteps all three: one open edit, explicit uploads of bundle + listing + screenshots + icon + release-notes + details, one commit. ~250 LOC, no plugin dependency. GPP stays applied (`play { enabled.set(false) }`) only so `processReleaseVersionCodes` continues to wire into the bundle task graph cleanly; all its real publish tasks are off.

### First-release caveats

Before the first production push from this pipeline, the developer must have:

1. **Created the app entry** on https://play.google.com/console (one-time). The API can publish updates but not create the app itself.
2. **Created a dedicated upload keystore** (`keystore/movement_logger_upload.keystore`) — Play Console's App Signing rejects keys reused across apps, so the ywesee shared `release.keystore` (still used by generika/sdif/parados) cannot be used here.
3. **Granted the service-account Admin (or Release manager)** on Movement Logger via Play Console → Nutzer und Berechtigungen.
4. **Completed the Play-Console-only forms** before production reviews accept anything: Datenschutzerklärung URL, Datenschutz/Data safety, Altersfreigabe/Content rating questionnaire, Zielgruppe/Target audience, App-Kategorie. The API can stage a draft release without these but cannot send it for review.

All four are done for Movement Logger (production releases flow since v0.0.48); the list only matters if the app entry is ever re-created.
