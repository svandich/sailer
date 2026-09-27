# Sailer

A minimal, from-scratch Android client for [u-cursos.cl](https://www.u-cursos.cl)
(Universidad de Chile's course platform), built as a lightweight alternative
to the official `cl.uchile.ing.adi.ucursos` app (reverse-engineered in the
sibling [`../ucursos`](../ucursos) workspace).

## Scope

Like the original app, Sailer does **not** reimplement u-cursos.cl's
features natively — it hosts the real site in a `WebView`, same as
`cl.uchile.ing.adi.ucursos.activities.MainActivity` does. What Sailer adds on
top:

- **Its own native login screen** instead of showing u-cursos.cl's login
  page directly.
- **Securely saved credentials**, so a network failure or an expired session
  can be recovered from automatically, without the user re-typing their
  password every time they open the app.

## How login works

u-cursos.cl has no public login API — authentication is a normal HTML form
at `https://www.u-cursos.cl/login`, and the official app just points a
`WebView` at it. Sailer keeps that same mechanism (so it doesn't depend on
reverse-engineering an undocumented API) but drives it programmatically:

1. The user enters their username/password in Sailer's own form
   (`LoginActivity`).
2. `auth/UCursosAuthenticator.kt` loads the real `/login` page in a `WebView`
   (hidden, in `LoginActivity`; the visible one, in `MainActivity`), fills in
   the page's own form fields via JavaScript, and submits that real form —
   Sailer never talks to a login endpoint directly.
3. On success, the username/password are saved with
   `auth/SecureCredentialStore.kt`, and the site's session cookie (set by
   u-cursos.cl itself) takes over for normal browsing in `MainActivity`.
4. On a later launch, `MainActivity` loads u-cursos.cl directly using the
   persisted session cookie. If that fails — network error, HTTP 5xx, or a
   redirect back to `/login` (expired session) — it automatically retries by
   replaying the saved credentials through step 2, with a few backed-off
   attempts. If that still doesn't get past `/login`, the saved credentials
   are treated as no longer valid and the user is sent back to the native
   login screen (pre-filled with their username) rather than looping forever.
   If it's a pure network problem (all retries also fail to load anything),
   an offline screen with a manual retry button is shown instead.

## Credential storage

`auth/SecureCredentialStore.kt` stores the username/password in
[`EncryptedSharedPreferences`](https://developer.android.com/topic/security/data)
(`androidx.security.crypto`), which:

- Encrypts both keys and values with AES-256, using a master key generated
  and held in the **Android Keystore** — the key material itself never
  leaves secure OS/hardware-backed storage and is not extractable.
- Is excluded from Android Auto Backup (`android:allowBackup="false"` in the
  manifest), so credentials never leave the device via a cloud/ADB backup.
- Only decrypts on-device, by this app, while unlocked — not recoverable
  from a copy of the raw file alone.

Credentials are only ever sent to `https://www.u-cursos.cl/login` itself (via
the real login form, over HTTPS), never to any third party. SSL errors are
never bypassed (`WebViewClient.onReceivedSslError` is deliberately left at
its default, connection-cancelling behavior).

## Building

Requires Android Studio, or just the Android SDK on the command line — no
particular JDK version is required beyond what Gradle 9.7.1/AGP 9.3.1 already
need (this has been verified: the project builds a real debug APK end to end
with `./gradlew assembleDebug`, including on a JDK as new as 26; Android
Studio also just uses its own bundled JDK for Gradle by default regardless of
what's on your system).

0. On Arch Linux, `./scripts/install-android-studio.sh` installs Android
   Studio and the SDK components this project needs (`platform-tools`,
   `platforms;android-34`, `build-tools;34.0.0`) — run it yourself in a real
   terminal (sudo/AUR confirmation required), then open a new terminal before
   continuing.
1. Open the `sailer/` folder in Android Studio, or run `./gradlew
   assembleDebug` directly (the wrapper here is a real, working Gradle 9.7.1
   wrapper).
2. Run on a device or emulator with network access to `www.u-cursos.cl`.

Note: AGP 9+ has Kotlin support built in, so there's no separate
`org.jetbrains.kotlin.android` plugin in these build files (it now errors if
present) — see the comments in `build.gradle.kts` / `app/build.gradle.kts` if
you're used to the older two-plugin setup.

## Project layout

```
app/src/main/java/cl/erz/sailer/
  SailerApplication.kt        - enables WebView debugging in debug builds
  auth/
    SecureCredentialStore.kt  - Keystore-backed encrypted credential storage
    UCursosAuthenticator.kt   - drives the real login page through a WebView
    LoginResult.kt            - Success / InvalidCredentials / NetworkError / Unexpected
  ui/
    LoginActivity.kt          - native login form
    MainActivity.kt           - hosts u-cursos.cl, auto-recovers on failure
```
