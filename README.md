# OneUisland — Dynamic Island for One UI 9

**English** | [Русский](README.ru.md)

An LSPosed module (legacy Xposed API) for One UI 9 / Android 17. It replaces the Now Bar capsule in the status bar with a dynamic island around the front camera, in the style of ColorOS 17 and HyperOS.

Get the ready-made APK from [Releases](../../releases).

## Features

- Shows live notifications in the island, like the Now Bar. Lock-screen-only live activities (`android.ongoingActivityNoti.style ≥ 2`) are skipped.
- Shows music, calls, screen recording and casting.
- Splits the island in two with a liquid bridge. A tap opens a stack of glass cards that flow out of the camera.
- ColorOS 17 style player with the app's own buttons and a seekable progress bar.
- The equalizer in the capsule reacts to the real audio.
- Shows apps' own RemoteViews layouts in the card (for example, navigation).
- Swipe left on the capsule to minimize an activity, swipe right to bring it back.
- Closing an app flies it into the island, using the One UI Home animation.
- Status bar icons fade out smoothly when the island covers them.
- Flashlight island: tap the flashlight to switch it on or off, drag along the beam to pick one of the brightness steps or slide to the flashlight to turn it off. The same card replaces the flashlight notification in the shade.
- Samsung Voice Recorder island: a red waveform that scrolls from right to left with the recording timer, pause and stop buttons in the card, and a short "saved" check after stopping. With Voice Recorder added to the module scope in LSPosed the waveform follows the real microphone level.
- Claude island: while Claude works in the Claude app (chats and Claude Code sessions), the island shows the Claude icon and a spinning four-dot indicator. The card shows the last three steps with the app's own icons, sliding in with a blur, questions with an "Answer in Claude" button, and the number of running background tasks. Add Claude to the module scope in LSPosed; a Claude Code session is followed while it is open in the app.
- Stopwatch shows milliseconds in the island, the card and the shade for the first hour. Metric notifications show the chip text the app sends.
- Frosted glass design (optional): see-through blurred cards with a dark fade at the top and a soft light edge at the bottom.
- Liquid glass design (optional): clear glass cards, and a clear blurred pill when "Blur instead of black" is on. The edges are drawn by an AGSL shader with a squircle refraction bezel, a light-facing specular rim and red/blue chromatic edges. Blur strength, edge thickness and chromatic edges can be adjusted.
- Recent Spotify playlists (optional): four square tiles under the Spotify player, tap one to play it.
- Detailed second capsule (optional): a mini equalizer for music, short time for timers (`5m`, `1h`) and the number for downloads.
- The player's seek bar is tinted with the album color and casts a cone of light behind its head.
- A fast swipe up closes the cards faster and makes the island bounce harder.
- Notifications in the island (optional, on by default): a notification that would pop up as a heads-up drops out of the island as a card with the sender, the message and the app's buttons. Reply opens a text field right under the card. A notification you don't act on stays on the island as a capsule with the app's icon: tap it to read or reply, swipe it left to remove it from the island (it stays in the shade). "Send test notification" in the settings posts a message to try it.
- LineageOS / AOSP Android 16 support: live activities and calls from the notification list, the platform blur (needs "Allow window-level blurs"), and a vertical position setting for ROMs that report the camera cutout wrong. While you drag that slider the island turns white with a black outline so the camera hole is easy to see.
- Charging started (optional, on by default): plugging in shows a green battery in the island that fills up to the charge level with a shimmer, then the bolt pops in; the card says Charging, Fast charging or Wireless charging.
- Short alerts: Do Not Disturb, sound / vibrate / mute, low battery (20% and 10%, shown for 5 s) and headphone battery (AirPods via LibrePods, Galaxy Buds, Fast Pair) with a red Low Battery alert at 10%.
- Live notifications in the shade are styled as dark glass island cards: light text, a thin rim and a solid background when blur is off. App layouts, expansion and actions are kept. Regular notifications and the lock screen keep the system look.

## Settings

Open the module settings from LSPosed:

- Island length (in characters) and vertical position.
- Notifications in island (with reply), test notification.
- Detailed second capsule.
- Cards: frosted glass or liquid glass design (blur, edge thickness, chromatic edges), recent Spotify playlists.
- Alert toggles: headphone battery (every unlock or only on connect), Do Not Disturb, sound mode, low battery.
- Island position: center or left after the clock (grows only to the right, stops before the camera). Tablets and the inner screen of foldables also get "around the front camera". Foldables have separate settings for the inner and cover screens. The left island, and the centered island on tablets and the inner screen, can use a blurred background instead of black.

## Installation

1. Download and install the APK from [Releases](../../releases).
2. Enable the module in LSPosed. Scope: **System UI**.
3. Restart System UI (no reboot needed), for example:
   `adb shell su -c "pkill -f com.android.systemui"`

## Building

You need JDK 17–21 and the Android SDK (platform 36). Set the SDK path in `local.properties`, then run:

```
gradlew.bat :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

To check the shade styling logic on a connected Android 17 device without installing the APK or restarting System UI, run `./tests/device/run.ps1` (PowerShell, JDK in `JAVA_HOME`). Details and the list of visual checks are in `tests/device/README.md`.

## Structure

- `app/src/main/java/dev/flexy/dynamicisland/xposed` — entry point and System UI hooks (`SystemUiHooks`).
  - `ShadeLiveCards` — live notification styling in the shade (Samsung ongoing activity / AOSP promoted ongoing).
- `app/src/main/java/dev/flexy/dynamicisland/island` — the island:
  - `IslandView` — drawing, animations, gestures;
  - `IslandController` — data and signals;
  - data sources: `OaSource`, `MediaSource`, `CastSource`, `AlertSource`;
  - blur, equalizer and app exit animation targets.
- `stubs` — Xposed API stubs, used only at compile time.
