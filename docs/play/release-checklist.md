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
| Release build as an Android App Bundle | **To do** | Today CI builds a debug APK. Needs a release build type (not debuggable, minified), `bundleRelease`, an upload key, Play App Signing. |
| Version | Done | `version.json` + git (D-059); `versionCode` only grows. |
| Wake lock | **To do** | Held for up to 12 h while tracking or spoofing; Android vitals penalises long wake locks. Shorten and renew it. |
| Name and icon rights | **To do** | The name and the dog come from "Пес Патрон". Check rights, and do not imply any official affiliation (said in the listing). |
| OpenStreetMap attribution in the app | Done | Map screen and Settings → About. |
| Closed test (12 testers, 14 days) | To do (personal account) | Invite testers by e-mail or a Google Group; they must stay opted in. |
