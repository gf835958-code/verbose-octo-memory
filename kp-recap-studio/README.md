# KP Recap Studio — Android v0.4 Alpha

This is an independently written Android media application. It is **not** a clone of KG Recap's copyrighted source/assets. The first release is an **alpha test**; a successful GitHub Actions compile is **not** proof that each feature works on a physical vivo V27.

## Modules

1. **Video Recap:** Android SAF video picker and preview, crop/fit and aspect ratios, trimming, user subtitles and timed SRT import/export, AndroidX Media3 H.264 + AAC MP4 export.
2. **Offline speech-to-text:** Android MediaExtractor + MediaCodec streaming to PCM WAV, MIT-licensed whisper.cpp Android AAR, auto-language recognition and timestamped SRT, optional 75 MB multilingual Whisper Tiny model downloaded with explicit user confirmation, no key needed.
3. **Myanmar translation:** ML Kit text-language auto-detection and an online HTTPS Cloudflare M2M100 worker client, with timing preserved per SRT cue. `cloudflare/recap-translate-worker.mjs` contains the separate backend. **Not deployed:** connected Cloudflare account returned 401 on Workers AI as of 2026-11-22. Until connected and deployed, no automatic Myanmar translation is available.
4. **TTS dubbing:** Myanmar voice via Android TextToSpeech (requires an installed Myanmar voice), speech chunks stitched into WAV, optional replacement of original video audio. Dubbing does **not** yet do per-segment lip-sync or guaranteed length matching.
5. **Audiobook studio:** original drawn narrator & desk, four studio palettes, optional user background and presenter image with chroma-key removal, 2D stage, 50 animated audience characters; optional actual energy-measured 100 ms audio-reactive waveform; MP4 export.
6. **PDF studio:** selectable-text PDF extraction using PDFBox Android, user-edited Myanmar PDF output using Android PdfDocument (does not preserve original page layout), scanned-PDF OCR via Tesseract and an optional installed language pack (eng/mya/tha etc), TTS WAV audiobook with chunk merging, audio-to-video handoff.
7. **Projects:** persistent user-project JSON snapshots, SAF URI restore, current-session export history and Gallery access.

## Limitations

- Cannot guarantee **any language**, unlimited video duration, instant transcription or translation quality. Huge files use substantial storage and compute.
- The Cloudflare hosted translation backend is not live, and service quotas/rate limits are not unlimited. Translation **may require internet**.
- Offline Whisper model is not included in the small base APK; downloading ~75 MB may use mobile data. First-run installation is required.
- Tesseract OCR models must be downloaded explicitly. OCR quality varies; scanned PDFs may be incomplete.
- Myanmar TTS uses whichever voice engine is installed in Android. Without a Myanmar voice, there is no dubbing.
- Green-screen removal currently supports imported **presenter photos**, not moving foreground videos.
- Rendering runs in the Activity; closing the app may interrupt long exports. Background ForegroundService still needs work.
- No evidence of full Android 15 on-device end-to-end acceptance, and no guaranteed bug-free release.

## Verify on Android 15

- Install latest **test APK** from GitHub prerelease: [APK assets](https://github.com/gf835958-code/verbose-octo-memory/releases/tag/kp-recap-v0.4-alpha).
- Verify launcher icon and Home → Editor → PDF → Projects navigation.
- Select a 20-second MP4. Generate a manual SRT and render to Movies/KP Recap Studio. Play the file and check audio/subtitles.
- In Whisper model setup, obtain a model once (import existing model or confirm 75 MB download), then transcribe a short video. Verify timestamps.
- Verify the PDF text extractor and direct Unicode PDF export. For image PDFs, download the matching Tesseract `.traineddata`, run OCR, inspect results.
- Select a WAV or MP3 and render a Dhamma Stage video; check that the actual waveform moves with audio.
- Test Android Burmese voice (if installed), chunked WAV generation, and dubbing into MP4.
- Test Cloudflare translation **only after the backend is authenticated/deployed**, with explicitly chosen text; ensure no API token appears in the APK.

### 3rd-party software notices

- `dev.ffmpegkit-maintained:whisper-android:1.0.0` and upstream whisper.cpp — MIT.
- `cz.adaptech.tesseract4android:tesseract4android:4.9.0` — Apache 2.0; Tesseract's separate traineddata model licenses must also be respected.
- `com.tom-roush:pdfbox-android:2.0.27.0` — Apache 2.0.
- AndroidX Media3 — Apache 2.0; Google ML Kit Language Identification terms apply.

Project must be tested before advertising production readiness. `Release` branch and automatic payments are intentionally not set up.
