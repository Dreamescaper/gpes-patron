# Google Play: Data safety answers

For Play Console → App content → Data safety. Based on the code and on the [privacy policy](../../site/privacy.html) as of 2026-10-02.
Recheck whenever the app starts sending anything new (a new network call, an SDK, analytics, crash reporting).

## What counts

Google counts data as **collected** only when it is transmitted off the device. Processing that stays on the phone is not collected.
Data that the user shares with an app of their choice through the system share sheet is a user-initiated action, not collection by us.

## Answers

| Question | Answer | Why |
|---|---|---|
| Does the app collect or share any of the required user data types? | **Yes** (approximate location, shared) | The road download sends the bounding box of a ≈5.6 km map square to the Overpass API server (a third party). |
| Is all of the user data collected by the app encrypted in transit? | **Yes** | HTTPS. |
| Do you provide a way for users to request that their data is deleted? | **No** (not needed) | No data is held by us. Recordings sent by users can be deleted on request (the policy says how); choose the closest option offered. |
| Location → Approximate location | Collected: **No** (it is sent to a third party only as part of the map request). Shared: **Yes**. Purpose: **App functionality**. Optional: **Yes** (the road map can be switched off). Processed ephemerally: **No answer needed for sharing** (the request is not stored by the app). | |
| Location → Precise location | Collected: **No**. Shared: **No**. | Processed on the device only. Optional recordings stay on the device. |
| Personal info, financial info, health, messages, photos, audio, files, calendar, contacts, app activity, web browsing | **No** | |
| App info and performance (crash logs, diagnostics) | **No** | The app has no crash reporting. Android vitals are collected by Google Play itself. |
| Device or other IDs | **No** | No advertising ID, no Android ID. |

Data-sharing wording to use: "Approximate location (the area of a map tile, about 5.6 km wide) is sent to a public Overpass API server to download road data. It is optional."

## Declarations to check

- **Ads:** none. **Account creation:** none. **Government apps / financial features / health:** none.
- **Permissions declarations:** location (core function: positioning for navigation), foreground service location (see [foreground-service.md](foreground-service.md)), `ACCESS_MOCK_LOCATION` (the app is a mock location provider selected by the user).
- **Privacy policy URL:** `https://dreamescaper.github.io/gpes-patron/privacy.html`. The policy is also linked in the app (Settings → About).
