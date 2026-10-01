# Screen Translate

An Android app that translates whatever is on your screen, like Google Assistant's old "translate this screen".
Long-press **Home** (or swipe up from a bottom corner) in any app. The translation appears right on top of the
original text, in the same place and colors. Tap any translation to see the original and copy either one.

- Text is read and translated **on the phone** with Google's ML Kit. Screenshots are never saved or uploaded.
- The internet is only used once per language to download its language pack (about 30 MB).
- Reads text in Latin letters (English, Indonesian, Malay, Spanish, French, German, Vietnamese…), Chinese, Japanese and Korean.
- Translates into any of ML Kit's ~59 languages. The language is detected automatically.

---

## 1. Get the APK

### Option A: let GitHub build it (no Android Studio needed)

1. Sign in at [github.com](https://github.com) (a free account is fine) and create a **new repository**, for example `screen-translate`.
2. On the empty repository page, click **"uploading an existing file"**. Drag in **everything inside** the unzipped `ScreenTranslate` folder,
   including the `.github` folder, then click **Commit changes**.
   - On a Mac, press **Cmd + Shift + .** in Finder to show the hidden `.github` folder.
   - If the `.github` folder didn't upload, click **Add file → Create new file**, name it
     `.github/workflows/build-apk.yml`, and paste in the contents of that file from the zip.
3. Open the **Actions** tab. A **Build APK** run starts on its own and takes about 5–8 minutes.
   If GitHub asks you to enable workflows, click the button to enable them.
4. When the run shows a green tick, open the repository's **Releases** (right-hand side of the main page) →
   **Screen Translate (latest build)** → **ScreenTranslate.apk**. Download it on your phone and open it to install.
   Allow "Install unknown apps" for your browser if asked. If Play Protect warns about an unknown app, choose **Install anyway**.

### Option B: Android Studio

Open the `ScreenTranslate` folder in Android Studio and wait for the sync to finish. Plug in your phone and press **Run**,
or use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The file appears in `app/build/outputs/apk/debug/`.

## 2. Set it up on the phone

1. Open **Screen Translate** and tap **Open assistant settings**.
2. Choose **Screen Translate** as the **Digital assistant app**. On the same page, turn on **Use screenshot**,
   and **Use text from screen** / **Analyze text on screen** if your phone has it.
   - Samsung: *Settings → Apps → Choose default apps → Digital assistant app → Device assistance app.*
     If you use gesture navigation, check the navigation bar settings for an option to open the assistant by swiping from a bottom corner.
3. Back in the app, pick the language to **translate to** (Indonesian and English are at the top of the list). You can
   tap **Download … now** on Wi-Fi so the first translation is instant.

## 3. Use it

Open any app, then long-press **Home** or swipe up diagonally from a bottom corner. On some phones it's a long-press of the power button.

- **Original** switches between the translation and the untouched screen.
- **→ Language** changes the target language, or the script to read if the text is Chinese, Japanese or Korean.
- **Tap a translation** to see the full original and translated text, with **Copy** buttons.
- **Back** or **✕** closes it.

## Good to know

- Apps that block screenshots (many banking apps, private/incognito browser tabs) can't be translated. Android simply hands over a blank screen.
- While Screen Translate is your assistant, the Home long-press no longer opens Google Assistant or Gemini.
  You can switch back at any time on the same settings page.
- A few apps that rely on the system's speech recognizer may not accept voice input while this is your assistant.
  Keyboard voice typing, such as Gboard's microphone, is not affected.
- The GitHub build is signed with a temporary key. If a newer build won't install over an older one, uninstall the old one first.
- Arabic, Thai, Cyrillic and Devanagari text isn't read. ML Kit's on-device text reader doesn't support those scripts.

## What's in the code

| File | What it does |
|---|---|
| `AssistantServices.kt` | Registers the app as a digital assistant (Android requires the three small services). |
| `TranslateSession.kt` | The overlay: receives the screenshot, draws translations over the text, bottom controls, copy sheet. |
| `ScreenTranslator.kt` | ML Kit text recognition, language detection, language-pack downloads and translation. |
| `AppSettings.kt` | Saved choices, language names, and the assistant-settings shortcut. |
| `MainActivity.kt` | The setup screen. |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub and publishes it to Releases. |

Built with Android Gradle Plugin 9.4, Gradle 9.6, ML Kit (text recognition 16.0.1, language ID 17.0.6, translate 17.0.3).
Minimum Android 8.0.
