# Ki Desktop Assistant

Ki is a Windows desktop companion with a draggable floating robot, local text chat, push-to-talk voice input, and spoken replies.

## What Ki can do now

- Chat with Qwen3:1.7b through Ollama running on this computer.
- Optionally enable **Web** in the chat composer to search Google and ask Ki about current information. If Google blocks the direct request, Ki automatically retries with DuckDuckGo. Ki uses the result snippets with the local Ollama model and shows source URLs in its reply. Web search is off by default; when enabled, your search query is sent to the search provider, while the AI request remains local.
- Open Calculator or Notepad after showing a confirmation prompt. The resizable dashboard shows battery, opens Task Manager, Snipping Tool, or Spotify, provides Windows media playback keys, searches Google in your default browser, and lets you add selected .exe app tiles with confirmation before launch. Spotify playback buttons use Windows media controls and may control whichever media player currently owns them.
- Choose a listening source in the dashboard’s Voice & sound settings: nearby voices through a microphone, laptop playback audio, or both. Ki transcribes English locally with Vosk. Spoken replies use a selected Windows voice. Emoji stay visible in chat but are skipped in spoken replies.
- Keep recent chat context during the current app session.
- Right-click Ki’s floating face for read-only Git status, changed-file diff, and recent commit views. Ki does not stage, commit, push, or modify files through these tools.
- Drop supported text/data files onto Ki’s face to add them to the selected project when Project mode is active, or to chat attachments otherwise. Ki briefly animates as it accepts the files.
- Show expressive face reactions while idle, listening, thinking, speaking, and briefly when clicked. Ki looks toward the pointer, blinks, and occasionally changes expression while idle. While idle, Ki may also randomly stand, walk around its spot for about 20 steps over 12–15 seconds, then return, sit, or tuck away its hands and feet. The walk covers a wider area while keeping Ki inset from screen edges. Interrupting a walk shows a reaction and animates Ki back to its starting position. A small red dot appears only while audio input is starting or active. When media is playing, Ki dances and its face turns into an animated audio-frequency display; the dance stops when playback stops. Ki also occasionally does a short spontaneous dance while idle. Ki reflects Windows battery/charging and CPU busy/idle status. Drag the floating face to move it; single-click for a playful reaction, double-click to open the chat/control panel, or right-click for Git tools.

Audio capture stays off until you press **Talk**, hold **Ctrl + Alt + Space**, or explicitly start ambient listening in Dashboard → Voice & sound. Choose the microphone and source in that dashboard setting. Laptop sound uses Windows audio loopback and captures whatever the computer is playing. Ambient listening can be stopped from the same setting; recognized words are added to the chat message box for review and are never auto-sent. You can use **Ctrl + Alt + Space** while the chat is minimized: the floating face listens, sends your speech, and speaks Ki’s reply without bringing the chat window forward. While Ki is speaking, press **Ctrl + Alt + Backspace** to stop the reply, or hold **Ctrl + Alt + Space** to interrupt it and start listening. Releasing the talk shortcut stops capture, processes the remaining audio, and sends the transcript automatically; wait for the listening status before speaking, especially on first use. Mic input uses shorter audio blocks and transcription ends after a brief phrase-ending pause; Ki drains the last captured audio before finalizing. It stops after 30 seconds or when you press **Stop**. Audio is processed in memory and is not saved or uploaded. The English speech model downloads on first use and is cached locally. Ki transcribes speech locally with Vosk; a first use may take longer while the model loads. The local Ollama API is contacted only at 127.0.0.1. Ki does not call a cloud AI API. Project mode edits only the files you select, only in Edit mode, and only after you review the diff and approve it. Existing files are backed up in .ki-backups. Ki does not delete files or run arbitrary terminal commands. Its Git tools run only read-only status, diff, and recent-log commands. It can open only Calculator or Notepad after you confirm, and reads Windows battery and CPU activity status for the face reaction.

Wake-word and clap detection are not implemented. Ambient listening is an explicit dashboard toggle: it stays off until you start it and shows a listening status while active.

## Android companion (starter)

The native Android app is in `android/` (Android 8.0 or newer). It has a dark charcoal-and-cyan chat UI, a small animated Ki that wanders across the companion card, saved pairing details, connection checks that distinguish PC/firewall issues from Ollama/model setup, UTF-8 text attachments up to 512 KB, and a **Updates** screen that checks GitHub Releases, downloads the APK, verifies its package/version, and hands installation to Android for your confirmation. Update checks use public GitHub release data; the repository and Android releases must be public. It does not expose desktop controls, run commands, or save uploaded files on the PC.

To pair, start Ki and open Dashboard → Device & activity → Phone companion. Enable phone access only when needed, then enter one of the displayed PC addresses and the pairing token in the Android app. If Windows asks about the firewall, allow Python on private networks only. The phone and PC must be on the same Wi-Fi/hotspot or a reachable USB-tethering network. USB file-transfer/MTP mode by itself is not a network connection.

The bridge is disabled by default, accepts only authenticated chat/status requests, and stops when Ki exits. The connection currently uses unencrypted HTTP with a random pairing token; use it only on a trusted private network and disable it when finished. After Android app changes, rebuild and reinstall the APK: open `android/` in Android Studio with Android SDK Platform 35 and JDK 17, or run `android\gradlew.bat :app:assembleDebug` from the repository root. The first build downloads Gradle 8.10.2 through the included wrapper.

### Publishing Android updates on GitHub

The `.github/workflows/android-release.yml` workflow builds and publishes a signed APK when a tag like `android-v0.1.1` is pushed. It publishes the file as `KiCompanion.apk`; the in-app updater looks for this asset on Android releases.

Before the first release, create a long-lived Android signing key and back it up securely. Never commit the keystore or its passwords:

    keytool -genkeypair -v -keystore "$HOME\KiCompanion-release.jks" -storetype JKS -keyalg RSA -keysize 2048 -validity 10000 -alias ki-companion

Add these GitHub repository Actions secrets: `KI_RELEASE_KEYSTORE_BASE64` (the keystore file encoded as one Base64 string), `KI_RELEASE_STORE_PASSWORD`, `KI_RELEASE_KEY_ALIAS`, and `KI_RELEASE_KEY_PASSWORD`. On Windows, copy the Base64 value without writing the key into the repository with:

    [Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\KiCompanion-release.jks")) | Set-Clipboard

Then push an Android version tag to start a release:

    git tag android-v0.1.1
    git push origin android-v0.1.1

The workflow assigns increasing Android `versionCode` values from the tag. Every future APK must be signed with the same keystore; losing it prevents Android from accepting app updates. The APK currently installed by copying the development/debug build is signed with a different key. Install the first GitHub-signed release as a fresh install (uninstalling the debug build removes its app data); after that, in-app updates can install over the app while preserving its data. A private GitHub repository cannot be checked by this public-release updater; do not embed a personal access token in the app.

## Requirements

- Windows 10 or newer
- Python 3.10 or newer
- Ollama for Windows with Qwen3:1.7b
- Android Studio with Android SDK 35 and JDK 17 to build the optional Android companion
- Internet access for one-time software and model downloads
- Windows microphone privacy access enabled for desktop apps when using microphone input
- PyAudioWPatch, installed with the requirements, for capturing laptop audio through Windows WASAPI loopback

## One-time setup

1. Install Ollama for Windows from https://ollama.com/download/windows
2. In a terminal, download the local chat model:

       ollama run qwen3:1.7b

   This downloads the model once. When Ki opens, a short animated startup screen waits for Ollama’s local server to respond, then opens Ki’s face. Ki starts Ollama’s server in the background, so you do not need to open Ollama or keep VS Code open. If Ollama does not become ready, the startup screen offers **Try again** or **Open Ki offline**. Ollama remains local at 127.0.0.1:11434.
3. In VS Code, open this folder and install the app and voice dependencies:

       cd C:/Users/Admin/Desktop/Ki_Companion
       python -m venv .venv
       .\.venv\Scripts\python.exe -m pip install -r requirements.txt

   The first time you press **Talk** or start ambient listening, Vosk downloads its small English recognition model (about 40 MB). Laptop sound capture uses Windows WASAPI loopback through PyAudioWPatch.
4. If the microphone is blocked, open Windows Settings, search for **Microphone privacy settings**, and enable access for desktop apps.


## Open Ki without a terminal

Double-click **Ki.vbs** in this folder to open Ki without a terminal. The **Ki** shortcut on your Desktop uses **Ctrl + Alt + K**. It launches Ki if closed and closes the running Ki instance when pressed again. A glitch-style loading screen appears while Ki starts Ollama’s local server; Ki’s face opens after the server is ready. If Ollama does not start, choose **Try again** or **Open Ki offline**. Ki runs without a terminal and does not require VS Code to stay open. Single-click Ki’s floating face for a playful reaction; double-click it to open chat and controls. Chat and controls share one compact window and topbar; the face remains draggable around the desktop. The panel uses solid charcoal and gray surfaces with clean spacing and simple controls. Resize the panel from its lower-right corner; dashboard tiles reflow into one column when it becomes narrow. The topbar switches between Chat and Control panel, and includes voice settings and panel controls. The control panel groups media playback, device and activity tools, search, and app shortcuts into distinct sections. The battery tile opens the current charge status, and Ki’s face shows a small charging badge when plugged in.

When Ki finishes starting, its face glitches into view in place, using the same cyan scanline effect as the startup screen. Press **Ctrl + Alt + K** again to quit; Ki glitches out in place. The shortcut still launches Ki when it is not running.

The chat/control panel has a taskbar button while it is open or minimized. Its **X** hides the panel and removes its taskbar button. A single click on Ki’s face only triggers a reaction; double-click to reopen the panel after hiding it. If the panel is minimized, use its taskbar button to restore it—clicking Ki’s face will not unminimize it.

To run it from VS Code instead, use a terminal:

    ./.venv/Scripts/python.exe main.py

When Ki starts, it greets you with the current local date and time. To approve or deny an open confirmation, hold **Ctrl + Alt + Space**, say only **approve** or **deny**, and release the keys. If Ki does not recognize either word, the confirmation stays open. Type in the chat as usual. Open the control panel and click **⚙ Voice & sound** to choose an installed Windows voice, preview it, adjust speaking speed, select a microphone, and turn spoken replies on or off. The chat’s **☰** button opens that same settings page. Use the **Quit Ki** button in settings to close Ki completely after confirming. Normal chat replies stream into the chat and Ki begins speaking at sentence breaks; the face animates while the voice is active. Project edit proposals are kept together so Ki can prepare the full review before showing it. In Voice & sound, choose **Microphone**, **Laptop sound**, or **Both**. Press **Talk** for one phrase. To listen continuously, choose a source and click **Start ambient listening** in the settings dialog; use **Stop ambient listening** when finished. Ki places recognized words in the chat box for review and does not send them automatically. Press **Talk**, say a phrase in English, and pause briefly. Or hold **Ctrl + Alt + Space**, wait for the **LISTENING** status, speak, and release either key to send. Ki places the transcription in the message box so you can correct it before pressing **Send**. Ask Ki for today’s date or current time; it reads the laptop’s local clock and time zone directly. Press **Stop** to cancel listening. Use the **☰** menu to turn spoken replies on or off. Audio source, microphone, voice, speed, and spoken-reply preferences are saved between launches. Use **Manage Windows voices** to open Windows speech settings, then **Refresh voice list** in Ki.

## OpenCode local agent

OpenCode is installed separately from Ki. In this project it defaults to `ollama/qwen3:1.7b`; the project configuration denies every non-Ollama provider, blocks web search/fetch, and asks before reading or editing files, running shell commands, or accessing folders outside this project. OpenCode displays those approval prompts in its own terminal or app. Ki does not click approvals for you.

The dashboard checks the Windows OpenCode launcher even when it is installed through npm and its launcher is not on PATH. The **Connect monitor** button is optional. Once connected, the `.opencode/plugins/ki-monitor.js` project plugin reports only allowlisted event names to Ki over loopback (`127.0.0.1`). It does not send prompts, responses, file contents, paths, commands, or keys. It monitors sessions started from this `Ki_Companion` folder only. Turn it off any time with **Disconnect monitor**. Monitoring is disabled by default.

## Ki Summary and local file analysis

Use **Ki Summary** in the dashboard to see counts and a recent timeline of OpenCode session, tool, approval, and error event labels received during the current Ki run. It keeps up to 100 event labels in memory for the current Ki run; it does not read OpenCode prompts, responses, code, paths, or commands.

Choose **Analyze files with Ki** in that window to select TXT, Markdown, JSON, CSV, or SQLite files. Ki prepares a read-only analysis prompt in chat; review it and press **Send** when ready. Analysis uses only the selected files, runs through local Ollama, and does not enable project edit mode. The existing file inspection limits still apply.

## Attach files

Use the **+** button in chat to select TXT, Markdown, JSON, CSV, or SQLite files. Ki reads only the files you select, locally, when you send the message. Text excerpts are limited to 8,000 characters; CSV inspection is limited to 10,000 rows and includes basic numeric summaries; SQLite is opened read-only and provides table/column names, up to five sample rows, and numeric summaries from at most 2,000 rows per table. Attachment contents are sent to Ollama at 127.0.0.1 and are not uploaded to a cloud AI service. The current text model cannot inspect images or open web links.

## Project mode

1. In chat, click **Project** and choose the folder Ki may work in. It opens at C:\Users\Admin\Desktop\Ki_Companion by default.
2. Use **Files** to pick individual files, or **Review folder** to let Ki find up to 10 supported text/code files in that folder for the next chat request. Folder review skips common generated/dependency folders and credential-style filenames. Ki reads the selected content locally when you send a message; the project label lists the chosen files when you hover over it.
3. Ask Ki to review or explain the project in chat. To request edits, turn on **Edit mode**, describe one small change, and press **Send**.
4. Review the complete diff. Click **Approve and save** or **Deny**; voice approval also works with **Ctrl + Alt + Space** and the words “approve” or “deny.” Nothing is written before approval.

Ki can edit only selected, fully supplied text files or create a new supported text file inside the chosen folder. Existing files are backed up under .ki-backups; new subfolders are not created. Ki does not delete files, run commands, or edit outside the selected project. Project context is limited to 10 files, 256 KB per file, and 12,000 characters total per request; large files are supplied as read-only excerpts. Folder review bounds its scan to 3,000 files and 500 directories, and skips dependency, cache, build, and backup folders.

## Files

- main.py — compatibility entry point used by Ki.vbs
- ki/shared.py — shared imports, icons, app paths, and helper functions
- ki/services.py — voice workers, local Ollama, web search, and OpenCode monitor
- ki/phone_bridge.py — opt-in, token-protected local Android chat bridge
- ki/chat.py — chat window and conversation flow
- ki/dashboard.py — dashboard, settings, Git, and activity dialogs
- ki/panel.py — combined chat/control panel window
- ki/robot.py — floating companion, animation, drag-and-drop, and hotkeys
- ki/startup.py — startup splash and startup flow
- ki/app.py — Qt application setup and single-instance launcher
- requirements.txt — PySide6, sounddevice, Vosk, and pyttsx3
- Ki.vbs — opens Ki without showing a terminal
- android/ — native Android companion app project
- .gitignore — ignores Python cache and local virtual environments
