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
  Hold the two phones together and NFC does the pairing, so there is no code to
  compare.
- **Choose what goes**: the whole wallet, one event, or the two seats out of twelve
  that belong to somebody else.
- **Assign** tickets to people, or let them claim one each, and record who has paid.
- **Update itself** from its own GitHub releases, verifying that what it downloaded
  is signed with the same key as the copy already installed.

## What it cannot do, stated plainly

A ticket is a bearer token: its value is a barcode a turnstile reads without asking
anybody. So once you send somebody a ticket, **you cannot take it back**. Deleting
your copy, marking it withdrawn and syncing that change does not remove the barcode
from their phone.

The app therefore says *withdraw*, not *revoke*, and tells you before an export that
the file cannot be recalled. The full reasoning is in the server repository's
[threat model](https://github.com/mateof/passvault/blob/main/docs/threat-model.md).

## Choosing what to share

Pairing two phones used to exchange both wallets entirely, which is right for two
devices belonging to one person and wrong for handing a friend a seat. Sharing now
starts from a scope: everything, one event, or tickets picked out of it with the
tick icon in the event screen. The same choice drives the `.tkpak` file.

Handing over some of an event's tickets gives the receiver a deliberately partial
view — the event and the tickets named, not the ones that were not — and the screen
says so before anything moves. Replay is built for that: an operation about a ticket
nobody sent is a gap rather than an error, and a device that later learns the rest
applies it then.

## Pairing by touch

The six digits exist because being on the same Wi-Fi authenticates nobody. They
work, and they are the step people get wrong: a glance and a "yes" looks exactly
like a check.

Holding two phones together is a channel an attacker on the network cannot reach
into, so the tap carries what the digits were protecting and the comparison stops
being needed. Android Beam — the API everybody remembers for this — was deprecated
in Android 10 and removed in 14, so this uses card emulation: one phone answers as
a contactless card and the other reads it.

What crosses is about a hundred bytes: an ephemeral public key, a single-use token
and an address. The tickets still go over Wi-Fi, because NFC moves bytes slowly and
a wallet is megabytes.

Both halves are needed and both are tested. The tapping side refuses a socket
presenting a key it did not read off the tag; the advertising side refuses a peer
that cannot return the token. Without the second, the tap would prove to the
receiver who it is talking to while the sender was still talking to anybody at all.
The order is load-bearing too: the advertiser demands the token before revealing
anything, or a peer that never tapped would be handed the very secret meant to
distinguish it.

A phone with no NFC compares the six digits exactly as before.

## Sharing over a local network

Two phones on the same Wi-Fi find each other, then **both show six digits**. Compare
them out loud before anything transfers.

That step is not ceremony. Being on the same network identifies nobody: on a café or
hotel Wi-Fi, any device can advertise itself under any name. The digits come from the
key exchange itself, so a device sitting in the middle cannot make both screens agree
— a mismatch is a detected attack, and the app refuses to continue.

The confirmation each phone sends after the users agree travels **inside** the encrypted
session rather than in the open, which is what turns the digits from a ritual into a
check: a relay holds a different key with each side, so its forwarded confirmation fails
to authenticate even if somebody waved the digits through.

What crosses is the signed operation log — the same thing a `.tkpak` carries and the same
request and response the server speaks. One mechanism, three transports.

### Driving a transfer without two phones

mDNS does not cross the emulator's NAT, so a workstation stands in for the second phone.
`scripts/local-transfer-peer.ts` in the server repository speaks the same wire protocol
using the server's own TypeScript crypto:

```bash
adb logcat -s PassVaultShare          # the port the app is listening on
adb forward tcp:9999 tcp:<port>
npx tsx scripts/local-transfer-peer.ts 9999
```

It prints the six digits it derived, and they have to match what the phone shows. That
makes it a cross-implementation check as well as a harness: two independent
implementations derive the digits from the same transcript, and a divergence is exactly
what a user would meet as "the two phones disagree".

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

Each release also carries a `.sha256` beside the APK, which is what the in-app
updater checks a download against.

## Updating from inside the app

There is no store to update through, so the app checks its own releases page. The
update screen — the icon beside the cloud in the wallet — reports the installed
version, asks GitHub for the newest one, and compares the two by tag.

Downloading is a separate press from checking: the APK is several megabytes and
nobody on mobile data should pay for it because they were curious what changed.

Three checks run on the downloaded file before the package installer sees it, and
only the third really protects:

1. **the digest** published beside the APK, which catches a truncated download;
2. **the package name**, which must be this application's — an APK for another
   package would install a second, unrelated app rather than updating anything;
3. **the signing certificate**, which must be the one the installed app carries.

Android enforces the third itself and would refuse the install anyway. Checking
first means the user reads a sentence explaining what is wrong instead of watching
a system dialog fail with nothing in it.

The install is never silent. Android shows its own confirmation for any app
installing an app, and that is the right place for the decision: something able to
replace itself unseen would be worse than the tap it saves.

A debug build says so and stops before downloading. It carries the `.debug`
package name and the debug key, so a published release could never replace it.

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
