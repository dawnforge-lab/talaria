# Building Talaria

## The app

You need:

- JDK 17 or later (CI uses Temurin 17).
- The Android SDK with Platform 37 (`sdkmanager "platforms;android-37.0"`). Point `ANDROID_HOME`
  at it, or put `sdk.dir=…` in `local.properties`.

```sh
./gradlew assembleDebug        # the debug APK, in app/build/outputs/apk/debug/
./gradlew installDebug         # installs it on a connected phone or emulator
```

The debug build's app id is `lab.dawnforge.hermes.debug`, so it installs beside a release build.
A release build (`assembleRelease`) is minified and needs your own signing config.

Without anything else, the app builds and runs with chatting, voice and everything that only
needs your dashboard. Notifications show as unavailable until you add Firebase (below).

### Optional settings in `local.properties`

`local.properties` stays out of git.

| Key | What it does |
|---|---|
| `hermes.relayUrl` | The push relay the app offers by default, such as `https://push.example.com`. You can still type another one in the app. |
| `hermes.mapsApiKey` | A Google Maps SDK key for map cards. Without one, places are rows that open your maps app. |

## Notifications

Push takes three parts that must agree: the app build, the relay and the plugin.

1. **A Firebase project of your own.** In the [Firebase console](https://console.firebase.google.com),
   create a project and add an Android app for each app id you'll install:
   `lab.dawnforge.hermes` and `lab.dawnforge.hermes.debug`. Download `google-services.json` and
   put it at `app/google-services.json` (it stays out of git). The build reads it directly; there's
   no Google Services Gradle plugin.
2. **A relay for that project.** In the same project, go to Project settings > Service accounts >
   Generate new private key, and run the [Talaria relay](https://github.com/dawnforge-lab/talaria-plugin/tree/main/relay) (in the plugin's repository)
   with that key and `FCM_PACKAGES=lab.dawnforge.hermes,lab.dawnforge.hermes.debug`. It needs a
   public HTTPS address the phone and your Hermes can both reach.
3. **The plugin on your Hermes**, in every profile that should notify the phone:

   ```sh
   hermes plugins install dawnforge-lab/talaria-plugin --enable
   hermes -p <profile> plugins install dawnforge-lab/talaria-plugin --enable
   ```

   Then restart the gateway and the dashboard so they load it.
4. **In the app**, open ⋮ > Notifications, enter the relay's address, and turn on notifications
   for every profile or the ones you want. The app registers with the relay and hands each
   profile a pairing code over your signed-in dashboard.

A build only receives pushes from a relay that uses the same Firebase project as its
`google-services.json`: Google refuses to deliver otherwise.

## Releases

Pushing a tag like `v1.2.0` runs `.github/workflows/release.yml`: it builds a signed release APK,
without Firebase settings, and attaches it to the tag's GitHub release. It needs four repository
secrets: `TALARIA_KEYSTORE_BASE64` (the keystore file, base64-encoded), `TALARIA_KEYSTORE_PASSWORD`,
`TALARIA_KEY_ALIAS` and `TALARIA_KEY_PASSWORD`. Keep the same keystore for every release, or
installed apps can't update. To sign a release build yourself, set the same names as environment
variables, with `TALARIA_KEYSTORE` as the keystore's path.

## Checks

CI runs these on every pull request; run them before you open one:

```sh
./gradlew spotlessApply spotlessCheck lint test assembleDebug -PwarningsAsErrors=true
```

`spotlessApply` fixes ktlint formatting; the rest must pass as they are.

## Live tests

A few tests in `core:data` talk to a real Hermes. They're skipped unless you give them a
properties file with the dashboard's `url`, `username` and `password`:
`-Phermes.live.file=<path>`. Some also need their own switch, such as `-Phermes.live.cron=true`,
and a few run a real (paid) model turn; the comments in `core/data/build.gradle.kts` say which.
They make their own chats and jobs and delete them afterwards. Read a test before running it
against a Hermes you care about, and keep the properties file out of the repo.
