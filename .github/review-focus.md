Talaria is an Android app (Kotlin, Jetpack Compose, Hilt, Room, OkHttp, kotlinx.serialization)
for Hermes Agent. Look especially for:

- Coroutines: work on the wrong dispatcher, scopes that outlive their screen, flows collected
  without lifecycle awareness, swallowed CancellationException.
- Compose: state kept in the wrong place, side effects outside LaunchedEffect and friends,
  remember without the keys it depends on, heavy work in composition.
- Security: secrets, passwords and tokens logged, stored unencrypted or put in URLs; trusting
  data from the dashboard or a push without checking it; WebView settings; exported
  components and intents other apps can send.
- Network and parsing: JSON fields assumed present, missing timeouts, errors that crash
  instead of showing a failure.
- Minified release builds: classes or enums found by name (Navigation route arguments,
  reflection) need keep rules in app/proguard-rules.pro.
- Names the app shares with the Talaria plugin and relay (routes, push data keys, encryption
  labels, tool names, links): a change to one needs the same change on the other side, said
  in the PR.
- User-facing text belongs in strings.xml, and changed behaviour needs a test.
