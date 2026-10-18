# SyncMusic

One phone shares whatever it's playing (YT Music, Spotify, anything); friends listen
along on their own Bluetooth headphones, in step with each other.

## Install
Open **Releases** in this repo on your phone and tap `SyncMusic.apk`.
Build 4 is the first signed release: uninstall older builds once, after that updates install over it.

## Use it
1. Turn Wi-Fi on on both phones (no network or internet needed).
2. **Sharing phone:** Share → Start sharing → allow audio and screen share → play music.
   Tap *Allow access* once to show the song and use play/pause/skip.
3. **Listening phone:** Listen → Scan code (the QR on the sharing phone).
   Next time: *Rejoin*. Alternatives: Find nearby phones, or Connect by IP on the same Wi-Fi.

## How it works
- Audio is captured with Android's playback-capture API, encoded with Opus (~128 kbit/s,
  raw PCM fallback) and sent over Wi-Fi Direct (or a shared Wi-Fi) with capture timestamps.
- Listeners sync clocks with the sharing phone and play every sound at capture time + 160 ms
  (+ adjustable delay), so all listeners stay in step. The sharing phone itself hears the
  original app directly, so it's ~160 ms ahead: Android can't silence another app while capturing it.
- Some apps block capture; the waveform stays flat and the app tells you.
