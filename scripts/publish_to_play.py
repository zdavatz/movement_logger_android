#!/usr/bin/env python3
"""
Publish a signed AAB + Play Console listing metadata to the Play Store.

Replaces Gradle Play Publisher's commit flow with direct Android Publisher v3
API calls so we can use `changesNotSentForReview=true`, which GPP 3.13
doesn't expose (added in 4.0, which requires Gradle 9). With the flag set,
releases land in Play Console as drafts that bypass the review-readiness
gate — required while the app's data-safety/content-rating/target-audience
forms are still incomplete during onboarding.

Inputs:
  --aab            Path to the signed AAB.
  --version-name   e.g. "0.0.9" (release name shown in Play).
  --sa-key-file    Service-account JSON key (default: play-service-account.json).
  --package        Android applicationId (default: ch.ywesee.movementlogger).
  --track          Track to publish to (default: internal).
  --contact-email  Contact email shown on the Play listing.
  --play-dir       Local Play listing directory (default: app/src/main/play).

Reads from --play-dir:
  default-language.txt                          → app's default locale
  listings/<lang>/title.txt                     → store title (≤ 30 chars)
  listings/<lang>/short-description.txt         → short desc (≤ 80 chars)
  listings/<lang>/full-description.txt          → full desc (≤ 4000 chars)
  listings/<lang>/video-url.txt                 → YouTube URL (optional)
  listings/<lang>/graphics/phone-screenshots/*.png  → 2-8 phone screenshots
  listings/<lang>/graphics/icon/icon.png        → 512x512 RGB PNG
  release-notes/<lang>/default.txt              → "what's new" for this version
"""
import argparse
import base64
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def get_access_token(sa_key_path: str) -> str:
    """Exchange the SA JWT for an OAuth access token."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    sa = json.load(open(sa_key_path))
    header = base64.urlsafe_b64encode(json.dumps(
        {"alg": "RS256", "typ": "JWT", "kid": sa["private_key_id"]}
    ).encode()).rstrip(b'=')
    now = int(time.time())
    claim = base64.urlsafe_b64encode(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": "https://oauth2.googleapis.com/token",
        "exp": now + 3600,
        "iat": now,
    }).encode()).rstrip(b'=')
    to_sign = header + b'.' + claim
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = key.sign(to_sign, padding.PKCS1v15(), hashes.SHA256())
    jwt = to_sign + b'.' + base64.urlsafe_b64encode(sig).rstrip(b'=')
    data = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt.decode(),
    }).encode()
    return json.loads(urllib.request.urlopen(
        "https://oauth2.googleapis.com/token", data=data
    ).read())["access_token"]


def api_call(token: str, method: str, path: str, package: str,
             body=None, content_type: str = "application/json",
             upload: bool = False) -> dict:
    """Wrap urllib.request with the Play API base URL and auth header."""
    base = "https://androidpublisher.googleapis.com"
    # Media uploads use a different base path.
    if upload:
        url = f"{base}/upload/androidpublisher/v3/applications/{package}{path}"
    else:
        url = f"{base}/androidpublisher/v3/applications/{package}{path}"
    headers = {"Authorization": f"Bearer {token}"}
    if body is not None:
        headers["Content-Type"] = content_type
    if isinstance(body, dict):
        body = json.dumps(body).encode()
    req = urllib.request.Request(url, method=method, headers=headers, data=body)
    try:
        resp = urllib.request.urlopen(req)
        raw = resp.read()
        return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        err_body = e.read().decode()
        print(f"\n{method} {url}", file=sys.stderr)
        print(f"  HTTP {e.code} {e.reason}", file=sys.stderr)
        print(f"  Body: {err_body[:1200]}", file=sys.stderr)
        e.body = err_body  # so callers can inspect without re-reading
        raise


def read_text(path: str) -> str:
    """Read a UTF-8 text file, strip trailing whitespace."""
    return open(path, encoding="utf-8").read().rstrip()


def publish(args: argparse.Namespace) -> None:
    token = get_access_token(args.sa_key_file)
    default_lang = read_text(os.path.join(args.play_dir, "default-language.txt"))
    listing_dir = os.path.join(args.play_dir, "listings", default_lang)
    release_notes_path = os.path.join(
        args.play_dir, "release-notes", default_lang, "default.txt"
    )

    # 1. Open an edit.
    edit = api_call(token, "POST", "/edits", args.package, body={})["id"]
    print(f"Opened edit: {edit}")

    try:
        # 2. Upload the AAB.
        aab_bytes = open(args.aab, "rb").read()
        upload_result = api_call(
            token, "POST",
            f"/edits/{edit}/bundles?uploadType=media",
            args.package,
            body=aab_bytes,
            content_type="application/octet-stream",
            upload=True,
        )
        version_code = upload_result["versionCode"]
        print(f"Uploaded AAB: versionCode={version_code} sha={upload_result.get('sha1')}")

        # 3. Assign to track with release notes.
        release_notes_text = read_text(release_notes_path) if os.path.exists(release_notes_path) else ""
        release_payload = {
            "name": args.version_name,
            "versionCodes": [str(version_code)],
            "status": args.release_status,
            "releaseNotes": [{"language": default_lang, "text": release_notes_text}]
                              if release_notes_text else [],
        }
        effective_status = args.release_status
        try:
            api_call(token, "PUT", f"/edits/{edit}/tracks/{args.track}",
                     args.package, body={"track": args.track, "releases": [release_payload]})
        except urllib.error.HTTPError as e:
            # While the app itself is still in Play Console "draft" state (no
            # production release has ever been approved), Play only accepts
            # production releases with status=draft. Fall back automatically
            # so the workflow doesn't get stuck on the first-ever production
            # push.
            if (e.code == 400
                    and args.track == "production"
                    and args.release_status != "draft"
                    and "draft app" in getattr(e, "body", "")):
                print("App is still in Play 'draft' state — retrying with "
                      "release-status=draft. Click 'Send for review' in "
                      "Play Console to promote.")
                release_payload["status"] = "draft"
                effective_status = "draft"
                api_call(token, "PUT", f"/edits/{edit}/tracks/{args.track}",
                         args.package, body={"track": args.track, "releases": [release_payload]})
            else:
                raise
        print(f"Assigned to {args.track} track as '{args.version_name}' "
              f"(status={effective_status})")

        # 4. App-level details (contact email + website + default language).
        details_body = {
            "contactEmail": args.contact_email,
            "defaultLanguage": default_lang,
        }
        if args.contact_website:
            details_body["contactWebsite"] = args.contact_website
        api_call(token, "PUT", f"/edits/{edit}/details", args.package, body=details_body)
        print(f"Set details: contact={args.contact_email}, "
              f"website={args.contact_website or '<unset>'}, "
              f"defaultLanguage={default_lang}")

        # 5. Listing texts + video URL.
        title_path = os.path.join(listing_dir, "title.txt")
        short_path = os.path.join(listing_dir, "short-description.txt")
        full_path = os.path.join(listing_dir, "full-description.txt")
        video_path = os.path.join(listing_dir, "video-url.txt")
        listing_body = {
            "language": default_lang,
            "title": read_text(title_path),
            "shortDescription": read_text(short_path),
            "fullDescription": read_text(full_path),
        }
        if os.path.exists(video_path):
            listing_body["video"] = read_text(video_path)
        api_call(token, "PUT", f"/edits/{edit}/listings/{default_lang}",
                 args.package, body=listing_body)
        print(f"Updated {default_lang} listing")

        # 6. Phone screenshots — delete the existing set, then upload ours.
        try:
            api_call(token, "DELETE",
                     f"/edits/{edit}/listings/{default_lang}/phoneScreenshots",
                     args.package)
        except urllib.error.HTTPError as e:
            if e.code not in (404,):
                raise
        screenshots_dir = os.path.join(listing_dir, "graphics", "phone-screenshots")
        shot_files = sorted(glob.glob(os.path.join(screenshots_dir, "*.png")) +
                            glob.glob(os.path.join(screenshots_dir, "*.jpg")))
        for shot in shot_files:
            data = open(shot, "rb").read()
            ctype = "image/png" if shot.lower().endswith(".png") else "image/jpeg"
            api_call(
                token, "POST",
                f"/edits/{edit}/listings/{default_lang}/phoneScreenshots?uploadType=media",
                args.package, body=data, content_type=ctype, upload=True,
            )
            print(f"  uploaded phone screenshot: {os.path.basename(shot)}")
        print(f"Total phone screenshots uploaded: {len(shot_files)}")

        # 7. App icon (512x512 PNG, no alpha).
        icon_path = os.path.join(listing_dir, "graphics", "icon", "icon.png")
        if os.path.exists(icon_path):
            try:
                api_call(token, "DELETE",
                         f"/edits/{edit}/listings/{default_lang}/icon", args.package)
            except urllib.error.HTTPError as e:
                if e.code not in (404,):
                    raise
            api_call(
                token, "POST",
                f"/edits/{edit}/listings/{default_lang}/icon?uploadType=media",
                args.package, body=open(icon_path, "rb").read(),
                content_type="image/png", upload=True,
            )
            print("  uploaded icon")

        # 8. Commit. Note: Google forced `changesNotSentForReview=true` to
        # be rejected as INVALID_ARGUMENT for this app — they auto-send for
        # review. So plain commit it is; relies on the app having all
        # Play-Console-side forms (data safety, content rating, target
        # audience, etc.) filled in.
        #
        # Play API quirk: `PUT /tracks/production` accepts status=completed
        # even while the app itself is in "draft" state on Play (no
        # production release ever approved). But `:commit` then rejects it
        # with `INVALID_ARGUMENT: "Only releases with status draft may be
        # created on draft app."`. Fall back to status=draft and retry.
        try:
            api_call(
                token, "POST", f"/edits/{edit}:commit",
                args.package, body=b"", content_type="application/json",
            )
        except urllib.error.HTTPError as e:
            if (e.code == 400
                    and args.track == "production"
                    and effective_status != "draft"
                    and "draft app" in getattr(e, "body", "")):
                print("Commit rejected because app is still in Play 'draft' "
                      "state — retrying with release status=draft. The "
                      "release will land in Play Console as a draft; click "
                      "'Send for review' in the web UI to promote.")
                release_payload["status"] = "draft"
                effective_status = "draft"
                api_call(token, "PUT", f"/edits/{edit}/tracks/{args.track}",
                         args.package, body={"track": args.track,
                                              "releases": [release_payload]})
                api_call(
                    token, "POST", f"/edits/{edit}:commit",
                    args.package, body=b"", content_type="application/json",
                )
            else:
                raise
        print(f"\nCommit OK. Release {args.version_name} (versionCode {version_code}) "
              f"is on the {args.track} track (status={effective_status}).")
    except Exception:
        # Best-effort cleanup so a partial edit doesn't linger.
        try:
            api_call(token, "DELETE", f"/edits/{edit}", args.package)
            print(f"Cleaned up edit {edit}", file=sys.stderr)
        except Exception:
            pass
        raise


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--aab", required=True, help="Path to signed AAB")
    p.add_argument("--version-name", required=True, help="Release name, e.g. 0.0.9")
    p.add_argument("--sa-key-file", default="play-service-account.json")
    p.add_argument("--package", default="ch.ywesee.movementlogger")
    p.add_argument("--track", default="internal")
    p.add_argument("--contact-email", default="zdavatz@ywesee.com")
    p.add_argument("--contact-website", default="https://ywesee.com/MovementLogger/MovementLogger",
                   help="Public-facing website URL on the Play listing.")
    p.add_argument("--play-dir", default="app/src/main/play")
    p.add_argument(
        "--release-status",
        default="completed",
        choices=["completed", "draft", "inProgress", "halted"],
        help="Roll-out status for the release. 'completed' = roll out to "
             "100% of the track's audience (default). 'draft' = stage in "
             "Play Console without rolling out (required when the app is "
             "still in draft state on Play, i.e. has never had a production "
             "release approved). Use 'draft' for first production push.",
    )
    publish(p.parse_args())


if __name__ == "__main__":
    main()
