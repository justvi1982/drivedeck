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
- **Phone:** the Bluetooth phone app gets the green phone icon and is pinned by default. To pick another: long-press the clock → **Choose phone app**.
- **Wi-Fi hotspot** (for the back screens): The round hotspot button above the grid button on the left switches it on or off (red = on). Long-press it for options, including switching it on automatically when the box starts (off by default). DriveDeck does this by opening the hotspot page in Settings and tapping the switch, so the home button service must be on. Tip: in the hotspot settings, switch off "Turn off hotspot automatically".
- **Light and dark mode** in settings: automatic (by sunrise and sunset), always light, or always dark.

## KidsDeck (back screens)

KidsDeck is a calm, always-dark home screen with a factory-style dark grille background for the kids' screens in the back (Android 9 and up): a big clock, large tiles for YouTube, Netflix and Prime Video, and nothing to get lost in. It also plays along with DriveDeck at the front.

**Install on a back screen**

1. Connect the screen to Wi-Fi (the TBox hotspot works) and open `https://github.com/justvi1982/drivedeck/releases/download/latest/KidsDeck.apk` in its browser.
2. Open the file and allow installing if asked.
3. Press Home and choose **KidsDeck**, then **Always**.
4. The grid button at the top opens **all apps** (scroll through them like on the TBox). Long-press an app there to put it on the home screen or take it off.
5. Parents: hold the clock for 3 seconds for settings: apps on the home screen, this screen's name (back right / back left), playing along on or off, and **media control**. Switch on **KidsDeck mediabediening** there so the front can pause and play Netflix and Prime in the back.

**Controlling the back from the front (only while parked, for example when charging)**

The TBox and the back screens must be on the same network: the back screens join the TBox hotspot. Give each back screen its own name in its parent settings (back right / back left).

On DriveDeck, tap the small screen button on the now-playing card. One panel has everything:

- **Screen:** right, left or both.
- **Mode:**
  - **Together:** the back plays the same YouTube video as the YouTube app at the front and follows play, pause and skipping. **Sound delay** makes up for the car's Bluetooth sound (press + if the back is ahead of the sound). Netflix and Prime in the back follow the front's play and pause.
  - **Back only:** the back plays on its own, so the front is free for Spotify and the like.
- **Play, pause, stop, volume and mute** for the chosen screen(s).
- **Play copied link:** in YouTube, Netflix or Prime, tap Share → Copy link, then this. YouTube videos play right away; Netflix and Prime open the title (and DriveDeck presses play a few seconds later). Long-pressing the screen button does the same without opening the panel. Share → More → **Play in the back** works too.
- **Open Netflix / Prime Video / YouTube** in the back.

If a YouTube video can't play outside YouTube, the back opens it in its YouTube app instead. The kids can close a video with ✕. Switch control from the front off on a screen in its parent settings.

## Builds

Every push to `main` builds both APKs (DriveDeck and KidsDeck) and publishes them at the links above. With the `KEYSTORE_PASSWORD` secret set, builds are signed with the key in `signing/` and install as updates over each other.

Map data © OpenStreetMap contributors.
