# Google Play: foreground service declaration (type `location`)

For Play Console → App content → Foreground service permissions. Required because the app targets Android 14+ and declares
`FOREGROUND_SERVICE_LOCATION`. Write the answers in English. The reviewers look at the video.

## Service

`gpes.app.service.DriveService`, `android:foregroundServiceType="location"`, notification "GPES: tracking the position" (while only
tracking) or "GPES is replacing your position" with a "Turn off" button (while spoofing).

## Use case

Closest listed use case: **Background location updates: Navigation**, a user-initiated feature that has to continue while the user
uses another app. If the form needs a custom text:

> The user turns on position protection in the app. While it is on, the app keeps estimating the vehicle's position from the
> phone's sensors and location sources, and provides it to the user's navigation app as the device location, so that turn-by-turn
> navigation continues when GPS is jammed or spoofed. The user can stop it at any moment from the app or from the ongoing
> notification, which has a "Turn off" button.

## Description of the functionality

- Started by the user (a button in the app, or by opening the Drive/Map screen, which only starts position tracking while the screen is
  open). Position tracking without spoofing stops when the app is left.
- Runs while the user navigates in another app. Stops when the user turns it off or when the app is closed from the notification.
- Shows a persistent notification at all times while running.

## User impact if the system defers or interrupts it

Navigation in the other app would lose the corrected position and show the jammed or spoofed one (or none), which can send the driver
the wrong way. The app removes its mock location when it stops and cleans up after an unexpected kill when it is next opened.

## Video (about 60–90 seconds, English subtitles or voice)

1. Open Settings → System → Developer options → Select mock location app → GPES Patron (one-time setup).
2. Open GPES Patron. Show the Drive screen with the position and the GPS verdict (tracking has started by itself). Show the Map tab.
3. Press "Turn on spoofing". Show the notification "GPES is replacing your position" with its "Turn off" button.
4. Switch to a navigation app. Show that it uses the position. (An emulator with `adb emu geo fix` is enough if there is no GPS indoors.)
5. Pull down the notification, press "Turn off", and show that the app returns to the tracking state / the notification disappears.

## Not allowed or not intended

The service does not track users in the background for advertising or analytics, does not upload location, and does not start by
itself in the background (it can only be started while the app is visible).
