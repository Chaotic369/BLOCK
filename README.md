# App Blocker

Android app that blocks apps and websites for a user-chosen time, resists uninstall while a lock is active, and reports screen time.

## Features

- Block any installed app for a timer you choose (15 minutes up to 7 days).
- Daily limit: pick how long an app may be used today; once that time is used up the app stays locked until midnight (needs usage access).
- Settings page: theme, locked screen, block-screen message, default lock time and daily limit, confirm-before-locking, permissions, and config backup.
- Config backup: Export saves settings (and optionally active locks) to a JSON file; Import loads one. Import never shortens a lock that is already running.
- Block websites two ways: a local DNS filter (works in every browser) and an address-bar check in supported browsers, with a generic address-bar scan for browsers that are not on the list.
- Uninstall protection in layers (see below).
- Screen time: daily totals, a 7-day average, the most used apps, and a short insight message.

## How the UI works

The whole UI is one HTML page, `app/src/main/assets/app.html` (the design from `app-blocker-demo.html`). `MainActivity` shows it in a WebView and the block screen (`BlockOverlayActivity`) shows the same page with `?overlay=1`.

The page talks to the device through `window.AndroidBridge` (`ui/web/WebBridge.kt`): installed apps and their icons, locks, blocked domains, permissions, screen time, uninstall protection and the Locked screen settings. Native code pushes changes back with `window.onNativeEvent`. Opened in a plain browser, the same file falls back to demo data, so it still works as a preview.

Locked screen: when "Show locked screen" is off, opening a locked app brings the app's home page forward with a short notice; when on, the block screen shows (with your cropped image, if set).

The page loads its font (Bricolage Grotesque) from Google Fonts and falls back to the system font while offline.

## Setup

1. Open the project in Android Studio (JDK 17) and run the `app` configuration. Android Studio creates the Gradle wrapper on first sync.
2. On first launch, grant usage access, accessibility service, display over other apps, and device administrator. The Permissions screen also offers the VPN approval (website blocking) and notifications.
3. Pick an app or domain, set a timer, and start the lock. Adding a domain triggers Android's one-time VPN consent dialog.

Grant permissions before you start a lock: while a lock is active, the guard closes Settings screens that show this app.

## How enforcement works

| Weak point | Mitigation |
| --- | --- |
| Uninstall | Device administrator makes Android refuse uninstall until the admin is turned off. |
| Turning off device admin or accessibility in Settings | Accessibility guard sends Settings and installer screens that show this app back to Home. |
| Turning off accessibility some other way (quick settings, Settings search) | A watchdog notices within 15 s, posts a heads-up alert, and keeps blocking locked apps by polling usage stats in the meantime. |
| Turning off device admin some other way | Immediate alert from the admin receiver, and the watchdog repeats it. |
| DNS filter killed or VPN revoked | Watchdog restarts it, or asks for VPN approval again. |
| Browser renames its address-bar id | Falls back to a bounded scan for an address-bar-like node. |

### Hard uninstall lock (device owner)

The layers above are soft: someone determined can still get around them. The only system-enforced option is making the app device owner. Do this once, from a computer with USB debugging on and no accounts signed in on the phone:

```
adb shell dpm set-device-owner com.example.appblocker/.admin.BlockerDeviceAdminReceiver
```

With that in place, while a lock is active Android refuses to uninstall the app and, on Android 14+, refuses force-stop and clear data. When no lock is active, the Permissions screen has a "Release device owner" button that hands ownership back so the app can be uninstalled normally. Without that step, removing a device owner normally needs a factory reset.

## Known limits

- Only one VPN can be active. If you turn on another VPN app, the DNS filter stops and the address-bar check is what remains. The watchdog will ask for approval again.
- Private DNS set to a fixed host, or a browser's own secure DNS, bypasses the DNS filter. The block screen warns about Private DNS; the address-bar check still applies.
- The DNS filter handles IPv4 UDP DNS only.
- Usage-stats fallback blocking is slower (about a second) than accessibility events and needs usage access and the overlay permission.
- Notifications need the Android 13+ notification permission to be visible.

## Notes

- The accessibility service reads only the browser address bar and window text needed to spot this app's own Settings pages. Nothing is stored or sent off the device.
