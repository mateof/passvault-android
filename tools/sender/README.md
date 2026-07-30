# Sender — a test harness, not part of the app

PassVault's two import routes both start the same way: another application hands over a
`content://` URI with a temporary read grant. That handover is the part `adb` cannot fake.
`am start -d file://…` is refused by scoped storage, and a `content://` typed on the command
line arrives without a grant, so the receiving activity opens with nothing it is allowed to
read. Three attempts at verifying imports from the shell alone stopped at that wall.

This is the smallest application that gets past it: it owns a file and sends it.

## Using it

```bash
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export JAVA_HOME=/path/to/jdk-21
./gradlew :tools:sender:assembleDebug
adb install -r tools/sender/build/outputs/apk/debug/sender-debug.apk

# Create the outbox with the app, then fill it. Order matters — see below.
adb shell am start -n com.mateof.passvault.sender/.SenderActivity
OUT=/sdcard/Android/data/com.mateof.passvault.sender/files/outbox
adb push entradas.pdf $OUT/
adb push entrada.tkpak $OUT/

# A PDF, the way a mail client sends one.
adb shell am start -n com.mateof.passvault.sender/.SenderActivity \
    -e file entradas.pdf -e action SEND -e mime application/pdf

# A .tkpak, the way a messaging app sends an extension it does not recognise.
adb shell am start -n com.mateof.passvault.sender/.SenderActivity \
    -e file entrada.tkpak -e action SEND -e mime application/octet-stream

# The same file through the other manifest filter, as "open with" does it.
adb shell am start -n com.mateof.passvault.sender/.SenderActivity \
    -e file entrada.tkpak -e action VIEW -e mime application/vnd.passvault.tkpak
```

| Extra | Default | Meaning |
| --- | --- | --- |
| `file` | — | Name inside the outbox, or an absolute path under it. Required. |
| `action` | `SEND` | `SEND` or `VIEW`, the two filters the app declares. |
| `mime` | guessed from the extension | What the sending app claims the file is. |
| `target` | `com.mateof.passvault.debug` | Receiving package. |

`adb logcat -s PassVaultSender` says what was sent, or why nothing was.

## Fixtures

The `.tkpak` files are the reference vectors from the server repository, `spec/vectors`; the
one used above opens with `sempre en Galiza`. A multi-page PDF with real barcodes comes from
that repository's `packages/ingest/test/fixtures.ts` — `ticketPdf` builds one with an
instructions page in front, which is the layout worth testing because it is the one where
splitting one ticket per page is wrong.

## Two traps that cost time

**Create the outbox with the app, not with `adb`.** `adb shell mkdir` makes a directory owned
by `shell`, and the app then cannot traverse its own folder: every send fails with "not a
file" while `adb shell ls` shows the file sitting right there. Launching the sender once with
no extras creates the directory with the right owner; pushes into it work from then on.
`rm -rf` the outbox and relaunch if it was ever created by hand.

**Wait for the receiving app, do not sleep and hope.** A cold start plus splash is around six
seconds on the emulator, and a screenshot taken before that captures the logo — which reads
exactly like an import that did nothing. Poll for something real instead:

```bash
until adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 &&
      adb exec-out cat /sdcard/ui.xml | grep -q "Event password"; do sleep 2; done
```

`uiautomator dump` returns "null root node" while an animation is running, which is itself a
signal: it means a progress indicator is on screen.
