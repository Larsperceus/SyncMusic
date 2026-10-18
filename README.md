# SyncMusic (POC)

Phone A plays music (YT Music, Spotify, anything) → SyncMusic captures that audio →
streams it over Wi-Fi → Phone B plays it on its Bluetooth headphones/speaker.

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo and upload this whole folder (keep the `.github` folder).
2. Open the repo's **Actions** tab → "Build APK" runs automatically (~3–4 min).
3. Open the finished run → download **SyncMusic-apk** → unzip → `app-debug.apk`.
4. Install it on both phones (allow "install unknown apps").

Or open the folder in Android Studio and press Run.

## Use it
1. Both phones on the same Wi-Fi, or one phone's hotspot with the other joined to it.
2. **Phone A:** Start hosting → allow audio → choose *entire screen* → play music.
   The status line shows the captured level; "silence" while music plays means
   that app blocks capture.
3. **Phone B:** connect Bluetooth headphones, type Phone A's IP (shown on Phone A), Start listening.

## Limits of this POC
- Android 10+ only. Some apps opt out of capture (often DRM/premium content); test yours.
- Listener is ~100–300 ms behind the host, plus Bluetooth latency. If Phone A also uses
  Bluetooth headphones the two end up close; on speaker vs. headphones you'll notice a gap.
- Uncompressed PCM (~1.5 Mbit/s): fine on local Wi-Fi, not meant for the internet.
- Several listeners can connect to one host.
