# Profanity Delay (Android prototype)

Broadcast-delay-style profanity filter for whatever's playing on your phone.
It captures system audio, holds it in a ~5 second buffer, runs an **offline**
speech recognizer (Vosk) on it, and mutes any ~500ms window where a flagged
word is detected — before it ever reaches your speaker/headphones.

## What "capture all audio" actually means here

The service now matches `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`,
`USAGE_ASSISTANT`, and `USAGE_NOTIFICATION` — that's as close to "everything
the phone plays" as the public Android API allows. It will **never** capture:
phone/VOIP call audio (OS-level block, no workaround exists), DRM-protected
content, or any app that opts out of capture. That's a platform restriction,
not a limitation of this code — no app on the Play Store can get around it
without root.

## About building this into an APK

I can't compile a `.apk` in this sandbox — that step needs the Android SDK,
which downloads from Google's servers, and this environment's network is
locked to a small allowlist (GitHub, npm, PyPI, etc.) that doesn't include
Google's. So the compile step has to happen on your machine. Once you've got
Android Studio installed and this project open, it's one click:
**Build → Build Bundle(s)/APK(s) → Build APK(s)**. Studio already has the SDK
and build tools it needs — nothing else to install.

## Building the APK via GitHub Actions (no Android Studio needed)

This project includes `.github/workflows/build.yml`, which builds a debug
APK in the cloud every time you push.

1. **Create a GitHub account** (free) if you don't have one: github.com/join
2. **Create a new repository** — github.com/new, name it whatever you like
   (e.g. `profanity-delay`), keep it Private if you'd rather nobody see it.
3. **Upload this project into it.** Easiest way if you don't know git: on
   the repo's page, click **"uploading an existing file"**, then drag in
   the *contents* of the unzipped `ProfanityDelay/` folder (not the zip
   itself — GitHub needs the actual files/folders, not a zip archive).
   Commit directly to `main`.
4. Click the **Actions** tab at the top of your repo. You should see a
   workflow run start automatically (triggered by your upload). If it
   doesn't, click **"Build APK"** on the left, then **"Run workflow"**.
5. Wait ~2-3 minutes for the green checkmark.
6. Click into the finished run, scroll to **Artifacts** at the bottom, and
   download **`profanity-delay-debug-apk`** — it's a zip containing
   `app-debug.apk`.
7. **Get it onto your phone**: email it to yourself, upload to Google
   Drive, or plug in via USB and copy it over — any way you'd move a file.
8. **Install it**: tap the `.apk` file on your phone. Android will block it
   the first time and prompt you to allow installs from that source
   (Settings → apps → "install unknown apps" for whichever app you used to
   open it) — approve that, then install.

This is a **debug build** — Android auto-signs it with a throwaway debug
key, which is fine for installing on your own phone but won't work if you
ever wanted to publish it to the Play Store (that needs a proper release
signing key, a separate step).

## Before you spend time on this

1. **YouTube Music might just not work.** `AudioPlaybackCapture` only grabs
   audio from apps that allow it. If capture comes back silent, that's Google
   opting YT Music out — there's no fix short of a rooted device with a
   different capture method. Test with a plain music player or a downloaded
   MP3 file first to confirm the pipeline itself works, *then* try YT Music.
2. This is a **prototype**, not a shipped filter. Offline STT on a music mix
   (vocals + instrumental, reverb, fast rap, etc.) will miss things and false-flag
   others. Treat it as "good enough to catch most of it," not a guarantee.
3. Whole 500ms chunks get muted, not surgical word-level cuts — you'll hear a
   blip of silence rather than a bleep.

## One-time setup

1. Install **Android Studio** (Hedgehog or newer) — this is a normal Android
   Studio project, open the `ProfanityDelay/` folder directly.
2. Download a small Vosk model (English, ~50MB is plenty):
   https://alphacephei.com/vosk/models — grab `vosk-model-small-en-us-0.15`.
3. Unzip it, and copy the **contents** into
   `app/src/main/assets/vosk-model-raw/` (create that folder).
4. Vosk needs the model on the real filesystem, not inside the APK, so add
   this one-time extraction step to `MainActivity.onCreate()` (copy from
   `assets/vosk-model-raw` to `filesDir/vosk-model` on first launch) — I left
   this out of the skeleton since it's ~15 lines of straightforward file-copy
   code and Android Studio's asset-copy snippets vary by Kotlin version; ask
   me and I'll drop it in if you want it done for you.
5. Edit `app/src/main/assets/profanity_list.txt` — add/remove words freely.
6. Plug your phone in (USB debugging on), hit Run in Android Studio.

## Using it

1. Start playing music in YouTube Music (or a test app/file first).
2. Open Profanity Delay, tap **Start Filter**.
3. Android will show a "this app wants to capture your screen/audio" prompt
   — that's the AudioPlaybackCapture permission, approve it.
4. Audio should now come out of a fresh 5-second-delayed pipeline instead of
   directly from YT Music.

## Files

- `MainActivity.kt` — requests the capture permission, starts the service.
- `AudioCaptureService.kt` — the actual pipeline: capture → buffer → STT →
  gated playback. All the tunable constants (`DELAY_MS`, `CHUNK_MS`) are at
  the top of the file.
- `assets/profanity_list.txt` — your word list.

## Realistic next steps if this doesn't fully work

- If YT Music blocks capture: point this at Spotify or local files instead
  (both historically allow capture), or fall back to a browser-based
  approach if you're open to that.
- If detection is too trigger-happy or too loose: swap the small Vosk model
  for a bigger one, or tune the 150ms padding around flagged words.
