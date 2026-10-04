# Release signing

The published APK (the `optimized` variant) is signed with the project's own key. Android
identifies an app by **package name + signing certificate**, so an installed copy can only be
replaced by a build carrying both the same `applicationId` and the same certificate.

## The key

| | |
|---|---|
| File | `keystore-relay-tester-release.p12` (PKCS12, 4096-bit RSA, SHA256withRSA, 10000 days) |
| Alias | `relaytester` |
| Certificate | `CN=Relay Tester, OU=Release, O=Relay Tester, C=CN` |
| Certificate SHA-256 | `05:C3:E5:67:05:AA:8C:42:05:23:F9:99:D9:50:CE:74:48:2C:42:F4:37:6A:C8:89:18:DB:10:E9:45:1D:69:DE` |
| Published package | `com.relaytester.app` (the `debug` variant keeps `.debug` and is a separate app) |

Verify any artifact with:

```
"$ANDROID_HOME/build-tools/<ver>/apksigner" verify --print-certs app/build/outputs/apk/optimized/app-optimized.apk
```

Signing is v2 + v3. v3 carries a key-rotation lineage: if this key is ever lost, a replacement
key can still be delivered as an update (Android 9+) by rotating with a proof-of-rotation
lineage, instead of telling every user to uninstall.

## Where the password lives

**Never in this repository** — this file is committed and the repository is public. PKCS12 uses
one password for the store and the key. The build reads it, in order, from:

1. a Gradle property — `relaytester.storePassword` in `gradle.properties`, normally the
   user-level one at `~/.gradle/gradle.properties` (outside every repository);
2. the JVM system property — `./gradlew :app:assembleOptimized -Drelaytester.storePassword=… -Drelaytester.keyPassword=…`;
3. the environment — `RELAYTESTER_STORE_PASSWORD` / `RELAYTESTER_KEY_PASSWORD`.

`keyPassword` falls back to `storePassword` when unset, which is the normal PKCS12 case.
`gradle.properties` in the project directory is committed, so put secrets only in the
user-level file. A debug build needs no password; building `optimized`/`release` without one
fails on the missing property name.

## Back this up, off this machine

Losing either the file **or** the password means no further in-place updates for anyone who
installed the app — the only remaining route is a new package name plus a reinstall for every
user. Keep a copy of both somewhere durable (a password manager plus an offline copy of the
file). The 2026-10-04 copy lives at `E:/AI/Zcode/backups/relay-tester-release-key-2026-10-04/`
(keystore + password note), which is a working-machine location, not an off-site backup.

## `keystore-relay-tester-120.p12` (unused)

The older key, kept only in case its password resurfaces. Two things about it are worth
recording because they are easy to get wrong:

- Despite the `.p12` name it is a **JKS** keystore (magic `FEEDFEED`). The signing config used
  to declare `storeType = "PKCS12"` for it, which would fail even with the right password.
- Its password is recorded nowhere on this machine; the values once written in this file did
  not open it. **It has never signed a published build**: every release from v1.0.0 through
  v1.5.0 is signed with the Android debug certificate
  (`40dcd408050cd0abeafdd1b4092ac9e89bec3af313ea87594c5f7fb2cbfac15d`), which is why it was
  safe to replace rather than recover it.
