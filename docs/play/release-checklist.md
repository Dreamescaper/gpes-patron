# Release to Google Play: checklist

Status as of 2026-10-02. "Done" means checked in this repository, not in Play Console.

| Step | Status | Notes |
|---|---|---|
| Developer account | Done (the owner's) | Personal accounts created after 2023-11-13 need a closed test with 12 testers for 14 days before production; organisation accounts do not. |
| Developer verification | Check in Play Console | Required from 2026-09-30 in some countries and globally in 2027 (Android developer verification). |
| Privacy policy and terms, two languages | Done | `site/`, published with GitHub Pages ([pages.md](pages.md)). Linked in the app (Settings → About). |
| Store listing text (English, Ukrainian) | Done | [store-listing.md](store-listing.md). Needs screenshots (phone, 2–8), a 512×512 icon and a 1024×500 feature graphic. |
| Data safety form | Drafted | [data-safety.md](data-safety.md). |
| Foreground service declaration and video | Drafted | [foreground-service.md](foreground-service.md). The video is still to be recorded. |
| Content rating (IARC), target audience, ads | To do in Play Console | Answers in the listing doc. |
| App access instructions for the reviewer | Drafted | In the listing doc: the reviewer has to select the mock location app. |
| Target API 36 | Done | `targetSdk = 36` (required for new apps and updates from 2026-08-31). |
| Release build as an Android App Bundle | Done | `release` is minified (R8) and signed from `GPES_UPLOAD_*`; CI builds `bundleRelease` every run and, with the upload key in the four CI secrets (done 2026-10-02), uploads a **signed** AAB as the `aab` artifact (D-062). |
| Version | Done | `version.json` + git (D-059); `versionCode` only grows. |
| Wake lock | Done | Bounded to 10 min and renewed every 5 min while a session runs (D-062). Still held for the whole of a long spoofing session. |
| Name and icon rights | **To do** | The name and the dog come from "Пес Патрон". Check rights, and do not imply any official affiliation (said in the listing). |
| OpenStreetMap attribution in the app | Done | Map screen and Settings → About. |
| Closed test (12 testers, 14 days) | To do (personal account) | Invite testers by e-mail or a Google Group; they must stay opted in. |

## Upload key and the signed bundle

The upload key is yours alone: never commit it. Create it once and keep a backup of the file and the passwords (losing it means a reset through Play support):

```bash
keytool -genkeypair -v -keystore gpes-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Build a signed bundle on your machine:

```bash
export GPES_UPLOAD_KEYSTORE=$PWD/gpes-upload.jks GPES_UPLOAD_STORE_PASSWORD=... GPES_UPLOAD_KEY_ALIAS=upload GPES_UPLOAD_KEY_PASSWORD=...
./gradlew :app:bundleRelease        # app/build/outputs/bundle/release/app-release.aab
```

CI secrets (Settings → Secrets and variables → Actions): `UPLOAD_KEYSTORE_B64` (`base64 -i gpes-upload.jks`), `UPLOAD_STORE_PASSWORD`,
`UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`. With them every run uploads `gpes-patron-<version>-signed.aab` as an artifact; without them, an
`-unsigned.aab` (Play will not take it). In Play Console enrol in Play App Signing when you upload the first bundle. `mapping.txt` (R8) is inside
the bundle for Play's crash reports and is also kept as a CI artifact.

## The upload key that exists now

Created 2026-10-02 on the owner's machine: the keystore and its password are in `~/gpes-upload-key/` (`gpes-upload.jks`, `password.txt`; alias `upload`;
the key and store password are the same). They are also in the four CI secrets, which GitHub never shows again, so **back up that folder** to a password
manager or an encrypted drive, and keep it out of the repository. Certificate SHA-256 (public, Play shows it after the first upload):
`3A:24:4C:AF:15:3C:61:EE:51:4E:0C:EF:89:70:92:E9:7B:1D:A6:8B:32:23:A7:E8:3F:D6:BF:1E:55:4C:43:80`. To get a bundle to upload: open the latest green run of
"Android build" → Artifacts → `aab` (a `…-signed.aab`).
