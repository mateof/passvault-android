# PassVault for Android

The main PassVault application. It manages event tickets on your phone and works
with no server at all: create events, import a multi-page PDF or a `.pkpass`, split
it into individual tickets, hand them to friends, and track who has paid.

Everything works offline. A server is optional, and joining one changes nothing
about how the app behaves when there is no signal.

| Component | Repository |
| --- | --- |
| This app | this repository |
| Server, web frontend, format specification | [mateof/passvault](https://github.com/mateof/passvault) |

## What it does without a server

- **Import** a multi-page PDF, an Apple Wallet `.pkpass`, or a photograph, and split
  it into separate tickets. Splitting is proposed, never applied blindly: some
  vendors put two passes on a sheet and others lead with a page of instructions, so
  you confirm the result.
- **Share** tickets as a single encrypted `.tkpak` file that travels over WhatsApp,
  Telegram, Bluetooth or email. The recipient needs the app and nothing else — no
  account, no server, no signup.
- **Transfer directly** to another phone on the same Wi-Fi, with no internet at all.
- **Assign** tickets to people, or let them claim one each, and record who has paid.

## What it cannot do, stated plainly

A ticket is a bearer token: its value is a barcode a turnstile reads without asking
anybody. So once you send somebody a ticket, **you cannot take it back**. Deleting
your copy, marking it withdrawn and syncing that change does not remove the barcode
from their phone.

The app therefore says *withdraw*, not *revoke*, and tells you before an export that
the file cannot be recalled. The full reasoning is in the server repository's
[threat model](https://github.com/mateof/passvault/blob/main/docs/threat-model.md).

## Sharing over a local network

Two phones on the same Wi-Fi find each other, then **both show six digits**. Compare
them out loud before anything transfers.

That step is not ceremony. Being on the same network identifies nobody: on a café or
hotel Wi-Fi, any device can advertise itself under any name. The digits come from the
key exchange itself, so a device sitting in the middle cannot make both screens agree
— a mismatch is a detected attack, and the app refuses to continue.

## The `.tkpak` format

Files are AES-256-GCM encrypted and Ed25519 signed. The format is specified in the
server repository at
[docs/spec/tkpak-v1.md](https://github.com/mateof/passvault/blob/main/docs/spec/tkpak-v1.md),
and this app implements it independently in Kotlin.

Both implementations run the same reference vectors from
[spec/vectors](https://github.com/mateof/passvault/tree/main/spec/vectors). If either
one drifts, its test suite fails — which is the failure a format written twice is
prone to, and the reason the vectors exist.

## Building

```bash
export JAVA_HOME="/path/to/jdk-21"
./gradlew :app:assembleDebug
```

`local.properties` with `sdk.dir` is gitignored; without it the build stops with
`SDK location not found`.

## Verifying the import routes

Both ways of importing — a `.tkpak` somebody sent and a PDF from a ticket vendor — begin with
another application handing over a `content://` URI. `adb` cannot produce one: scoped storage
refuses `file://`, and a `content://` typed at the shell arrives without the read grant that
makes it openable. So the tests need an application to do the sending, and
[`tools/sender`](tools/sender/README.md) is it — a separate APK, never shipped, driven entirely
from `adb`.

It is worth the trouble. Running the real thing through it is what showed that the app read a
vendor PDF's QR and PDF417 and silently dropped its Aztec, and that sharing a document opened
the app on a blank screen for ten seconds with nothing to say it was working. Neither is
visible from a compiler or a unit test.

A release build is unsigned unless `KEYSTORE_PATH` is set, so working on the app
never requires the production key.

## Signing and releases

Signing is set up once, from a script kept **outside this repository** and gitignored. It
generates the keystore with `keytool`, sets four repository secrets — `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_PASSWORD` and `KEY_ALIAS` — and writes the password and a base64
copy of the keystore to a local file for safekeeping. Neither the keystore nor that file is
ever committed: a keystore committed once is a keystore that has to be replaced.

The script itself holds no secret, but it carries the certificate's distinguished name and
the paths of the machine it runs on, which is reason enough for it not to be public. The
release workflow only needs the four secrets to exist.

Merging to `main` publishes a release automatically. `versionName` in
`app/build.gradle.kts` is the version; `versionCode` comes from the CI run number,
because a version code somebody forgot to raise is the usual reason an update refuses
to install. The workflow stops early if the tag already exists rather than failing
after a full build.

**Keep the keystore and its password.** Android refuses an update signed with a
different key, so losing them means never updating the installed app again.

## Languages

Galician, Spanish and English. Galician is the default and the fallback, so
`res/values` holds the Galician strings rather than English ones — this is a
Galician-first application, not an English one with translations.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

## Refreshing the reference vectors

`app/src/test/resources/vectors` is a copy of `spec/vectors` from the server
repository. After an intentional change to the format, regenerate them there with
`npm run vectors:generate` and copy them across:

```bash
cp -r ../passvault/spec/vectors/. app/src/test/resources/vectors/
./gradlew :app:testDebugUnitTest --tests '*TkpakVector*'
```

Copied rather than shared through a submodule or a published artefact, because the
alternative costs a release cycle on every format change to solve a problem that a
`cp` and a failing test already solve. If that stops being true, the specification is
the thing to keep stable — not the copying mechanism.
