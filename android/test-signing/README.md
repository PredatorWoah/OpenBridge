# Test signing key

`debug.p12` signs debug builds (CI and the **Test APK** workflow), so each test APK installs over the last one. It is
deliberately public and worthless: password `android`, alias `androiddebugkey`. It can never sign a release. The release
workflow only publishes an APK signed with the OpenBridge release key, whose SHA-256 is
`4e41b5c80d00e40b264abfe65c986a8b6d1f4fdff7e6d17eeb66105ae4bb1685`.

A phone with the release installed must uninstall it once before installing a test APK, and uninstall the test APK
once before going back to a release (Android refuses an update signed with a different key).
