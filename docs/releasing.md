# Releasing

1. Add a section for the new version to `CHANGELOG.md` and set the same version in `android/app/build.gradle.kts` (`versionName`, and increase `versionCode`) and `macos/Info.plist` (`CFBundleShortVersionString`, and increase `CFBundleVersion`).
2. Merge to `main`, then run **Actions → Release → Run workflow** with the version (for example `1.0.0`).

The workflow checks the versions and changelog, runs the tests, builds both apps and publishes them with checksums. From 1.0.0 it refuses to publish without the Android release key below; 0.x previews fall back to a debug-signed APK with a warning.

## Android release key (one-time setup)

Android only installs an update signed with the same key as the installed app. The release key is kept in three encrypted repository secrets and is never committed.

**1. Create the key on your Mac.** You need `keytool` from a Java runtime. Android Studio includes one:

```sh
KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
# Without Android Studio: brew install openjdk, then KEYTOOL="$(brew --prefix openjdk)/bin/keytool"
"$KEYTOOL" -genkeypair -v -keystore openbridge-release.p12 -storetype PKCS12 \
  -alias openbridge -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=OpenBridge, O=OpenBridge"
```

It asks for a password twice. Use a long random one from your password manager.

**2. Back it up now.** Store `openbridge-release.p12` and its password in your password manager (or two separate safe places). If the key is lost, no future version can ever update existing installs; if it leaks, someone could publish fake updates.

**3. Add the secrets.** On GitHub: repository **Settings → Secrets and variables → Actions → New repository secret**, three times:

| Name | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Run `base64 -i openbridge-release.p12 \| pbcopy` and paste. |
| `ANDROID_KEYSTORE_PASSWORD` | The password from step 1. |
| `ANDROID_KEY_ALIAS` | `openbridge` |

**4. Check it.** The next release's notes show **Android signing certificate (SHA-256)**. Compare it with your key:

```sh
"$KEYTOOL" -list -v -keystore openbridge-release.p12 -alias openbridge | grep SHA256
```

The first release signed with this key cannot install over earlier test builds: uninstall the old app once. Every later update installs normally.

The Mac app is ad-hoc signed. Signing and notarizing it needs a paid Apple Developer ID and is not set up.
