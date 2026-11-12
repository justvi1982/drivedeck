# DriveDeck

A minimal, iPhone-style home screen (launcher) for the Carlinkit TBox Plus and other Android car boxes: pinned and recent apps on the left, a heading-up map that shows Google Maps' next turn, a now-playing card with album art, and a wide Flitsmeister alerts tile. Light by day, dark after sunset.

## Install on the TBox

1. On the TBox, open the browser and go to `https://github.com/justvi1982/drivedeck/releases/download/latest/DriveDeck.apk`
2. Open the downloaded file and allow installing from the browser if asked.
3. Press Home and choose **DriveDeck**, then **Always**.
4. Allow location when asked (for the map, and to work out sunrise and sunset).
5. Tap the now-playing card once and switch on **DriveDeck now playing** so it can show songs, album art and Flitsmeister alerts.
6. For the home button and swipe up: long-press the clock → **Home button and swipe up** → switch on **DriveDeck home button and swipe**.
7. For recent apps on the left: tap the clock icon on the left rail → switch on DriveDeck under **Usage access**.

If Android blocks step 5, 6 or 7 as a "restricted setting" (normal for apps installed from the browser on Android 13): Settings → Apps → DriveDeck → ⋮ menu → **Allow restricted settings**, then try again.

New builds publish to the same link and install as updates.

## Using it

- Tap the map to open navigation. Long-press it to pick a different navigation app.
- The left rail shows up to 2 pinned apps (Phone by default), then your most recent apps. Long-press an app (in the rail or in all apps) to pin or unpin it.
- Long-press the clock or the grid button for settings, including switching back to the original Carlinkit home screen.
- In any app, tap the floating home button or swipe up from the middle of the bottom edge to go home. Drag the button to either side; it fades when you're not using it.
- Under **Home button and swipe up → Hide in apps…**, pick apps (like a video player) where neither should appear.
- The wide tile shows the latest Flitsmeister alert; tap it to open Flitsmeister. Long-press it to use a different app. DriveDeck opens Flitsmeister once when the box starts and comes straight back, so alerts arrive in the background.
- While Google Maps is navigating, the map shows the next turn, distance and arrival time. The map turns with the car.
- If the home button or swipe doesn't appear: long-press the clock → **Home button and swipe up** → **Status**, and take a photo.
- **Light and dark mode** in settings: automatic (by sunrise and sunset), always light, or always dark.

## Builds

Every push to `main` builds the APK and publishes it at the link above. With the `KEYSTORE_PASSWORD` secret set, builds are signed with the key in `signing/` and install as updates over each other.

Map data © OpenStreetMap contributors.
