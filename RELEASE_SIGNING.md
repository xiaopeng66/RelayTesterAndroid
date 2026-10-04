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

## Every published version shares this identity

Since 2026-10-04 every release on the release pages — v1.0.0 through v1.6.0 — carries package
`com.relaytester.app` and is signed with the key above. That is what lets any one of them replace
any other in place (both `adb install -r` and the in-app updater require the matching package
name *and* certificate), so a user on any published version can move to the newest one without
uninstalling.

To reach that state v1.0.0–v1.5.0 were **rebuilt from their own tags** on 2026-10-04 and
republished:

- the `optimized` build type's `applicationIdSuffix = ".debug"` and
  `versionNameSuffix = "-optimized"` were removed, and its signing config switched from `debug`
  to `release`;
- the code, `versionCode` and `versionName` are unchanged from the first publication;
- Android builds are not byte-reproducible, so the rebuilt files have **new byte counts and
  SHA-256 digests**. Each release page's `## 下载` block carries the values of the file that is
  currently published, and the note above it says the build was rebuilt.

If a historical version ever has to be rebuilt again: in a detached worktree of its tag
(`git worktree add --detach <dir> <tag>`), drop those two suffixes and point the `optimized` type
at `signingConfigs.getByName("release")` — adding the `signingConfigs`/`secret()` block above if
the tag predates it — copy `local.properties` and the keystore in, build `assembleOptimized`, then
confirm with `apksigner verify --print-certs` that the package and certificate match this file
before publishing, and update the release notes' digest/size to the new artifact.

**Copies installed before that date are a different app**: package `com.relaytester.app.debug`,
signed by the Android debug certificate
(`40dcd408050cd0abeafdd1b4092ac9e89bec3af313ea87594c5f7fb2cbfac15d`, DN `CN=Android Debug`,
v2 only). Nothing published can update them in place, so each such user needs one manual install
of a current release (export the configuration from the old app first — see the README); every
update after that happens inside the app.

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
  not open it. **It has never signed a published build**: as originally published, every release
  from v1.0.0 through v1.5.0 was signed with the Android debug certificate
  (`40dcd408050cd0abeafdd1b4092ac9e89bec3af313ea87594c5f7fb2cbfac15d`) — those files have since
  been rebuilt with the release key (see above) — which is why it was safe to replace rather than
  recover it.
