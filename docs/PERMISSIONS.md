# Permissions, and why each one is here

Written because the app asks for a permission combination that security scanners
treat as a known-malware signature. If you are auditing this app, this file is
the short version: three permissions, each with one purpose, each verifiable by
reading the code.

## The combination that trips scanners

This app declares, in one package:

| Permission | Used by | Why |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | `HudOverlayService` | Draws the HUD above the game. |
| `BIND_ACCESSIBILITY_SERVICE` | `TouchAutomationService` | Dispatches taps/gestures. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | `CaptureService` | Screenshot capture. |

**Overlay + accessibility + screen capture is the exact triad Play Protect
classifies as a banking-trojan pattern.** On a sideloaded app with no Play
reputation, that combination gets blocked before it can install.

This is a false positive in the sense that matters here: none of these are used
to read other apps, capture credentials, or exfiltrate. But it is not a *harmless*
false positive, and you should verify rather than trust this document.

## Verify it yourself

Every permission, checked against source:

```bash
# 1. Overlay: does it read other apps' windows?
grep -n "canRetrieveWindowContent" app/src/main/res/xml/accessibility_service_config.xml
#   -> false. The service cannot read window content at all.

# 2. Does the app make network calls to anywhere?
grep -rn "https\?://" app/src/main/java/ | grep -v "schemas.android.com"
#   -> the only destinations are the relay host/port the USER configures,
#      and Minecraft servers.

# 3. Is the accessibility service exported or reachable by other apps?
grep -n "BIND_ACCESSIBILITY_SERVICE" -A2 app/src/main/AndroidManifest.xml
#   -> the permission makes it unbindable by anything but the system.
```

The source is public. Nothing here needs a secret to check.

## Why MediaProjection cannot simply be dropped

It is the most suspicious-looking of the three and the least removable. On
Android 14+ with `targetSdk 35`, a MediaProjection foreground service
**requires** `FOREGROUND_SERVICE_MEDIA_PROJECTION` to be declared; the service
cannot run without it. Android manifest permissions are static — there is no
"declare it only when the feature is enabled".

Removing it would break `misc.screenshot_share` and `misc.screenshot_tools`,
which are currently delivered. So it stays, and this paragraph is here so the
decision is on the record rather than looking like an oversight.

## What the app does with each permission

### `SYSTEM_ALERT_WINDOW` — HUD overlay

`ui/HudOverlayService`. Draws the lines the native HUD renderer produces
(`NativeHud.renderHudLines`) onto a `TYPE_APPLICATION_OVERLAY` canvas at 4 Hz.

It is `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, so it cannot receive input and
cannot intercept taps — it draws, it does not sit between you and the game.

Started only by hand from the HUD screen, and only once
`Settings.canDrawOverlays()` is true. The permission is requested by sending you
to system settings and then re-reading the real answer on resume; the app never
assumes it and never starts itself.

### `BIND_ACCESSIBILITY_SERVICE` — input injection

`runtime/cheat/TouchAutomationService`, label "Xykell Touch".

- `canRetrieveWindowContent="false"` — it cannot read any app's screen content.
- It exists to call `dispatchGesture`, which is how a tap reaches the game
  without forging a protocol packet.
- Enabled by hand in system Accessibility settings. Never auto-started.
- `BIND_ACCESSIBILITY_SERVICE` means only the system can bind it.

This is the same mechanism a legitimate accessibility tool uses. It is also the
mechanism abuse tools use, which is exactly why the flag is off and why the
service does nothing but inject taps.

### `FOREGROUND_SERVICE_MEDIA_PROJECTION` — screenshots

`runtime/capture/CaptureService`. Android's own consent dialog is required
every time: the app cannot capture a screen without you approving it at the OS
level. The token is consumed once per capture.

## Installing anyway

Play Protect's block here is a heuristic on the permission pattern, not a finding
about this app. If you decide to proceed:

1. **Better**: Settings → Security → App protection → *Scan apps using Play
   Protect* set to **Scan only**, not *Scan and block*. You keep scanning for
   everything else.
2. Verify the artifact hash against the one in `docs/RELEASE-0.2.0.md`.
3. Or install once with protection off, then turn it back on.

Note the tradeoff plainly: turning Play Protect off disables scanning for **all**
apps on the device, not just this one. That is a real reduction in protection on
a phone that presumably holds your accounts.

## Signature stability

The first release used a per-build debug key, which meant every rebuild looked
like a different app to Android. The build script now uses a persistent release
keystore, created once and never committed. See `docs/RELEASE.md`.