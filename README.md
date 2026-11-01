# Ki & Tot

**A local-first AI companion for Windows, with an Android companion for working on the go.**

[![Latest Android release](https://img.shields.io/github/v/release/gitieeetot/Ki_Companion?display_name=tag&label=Android%20release)](https://github.com/gitieeetot/Ki_Companion/releases/latest)
[![Android release workflow](https://github.com/gitieeetot/Ki_Companion/actions/workflows/android-release.yml/badge.svg)](https://github.com/gitieeetot/Ki_Companion/actions/workflows/android-release.yml)

**[Download Tot for Android](https://github.com/gitieeetot/Ki_Companion/releases/latest/download/TotCompanion.apk)** · [Browse all releases](https://github.com/gitieeetot/Ki_Companion/releases)

Ki is a Windows desktop companion powered by local Ollama models. Tot is its Android companion for chat and remote project work. Pairing is opt-in; actions that change PC files or approve agent requests require your review.

## Contents

- [What’s included](#whats-included)
- [Requirements](#requirements)
- [Install and run Ki](#install-and-run-ki)
- [Use Tot with Ki](#use-tot-with-ki)
- [Use the local OpenCode agent](#use-the-local-opencode-agent)
- [Privacy and security](#privacy-and-security)
- [Build and test](#build-and-test)
- [Publish an Android release](#publish-an-android-release)
- [Repository layout](#repository-layout)

## What’s included

### Ki for Windows

- Chat through Ollama using Qwen3:0.6b by default; Qwen3:1.7b is available for more demanding tasks.
- Optional image analysis with the local Moondream vision model.
- Optional web search. Search queries go to the search provider; AI responses are generated locally.
- English speech recognition with Vosk, Windows text-to-speech, and optional microphone or system-audio input.
- Review-first project mode for supported text files in a selected folder. Ki shows a diff before saving and backs up existing files.
- A desktop companion with voice controls, dashboard tools, and read-only Git status, diff, and history.

### Tot for Android

- Chat and voice controls that use Ki and Ollama on the paired PC.
- On-device text recognition for photos, Android sharing to Google Lens, and optional local image analysis through Ki.
- **Browse Projects in Tot:** browse existing UTF-8 text files under `%USERPROFILE%\Projects`, edit supported files up to 256 KB, review the diff, and explicitly confirm before saving. Tot does not create or delete files.
- **Termux SSH:** open a Windows terminal on the PC, starting in `%USERPROFILE%\Projects`.
- **Remote VS Code:** open a VS Code Tunnel in the phone browser.
- OpenCode session and permission review, plus confirmed Git actions for the repository associated with a selected session.
- An optional floating buddy and an in-app updater for public GitHub releases.

## Requirements

- Windows 10 or later.
- Python 3.10 or later.
- Ollama for Windows and a compatible local model.
- An internet connection for initial downloads and optional web search.
- Windows microphone privacy access for microphone input.
- Android 8.0 or later to install Tot.
- Android SDK Platform 35 and JDK 17 to build Tot.

Ki and Tot can run local AI without a paid AI API. Internet access may still be needed to download software and models, use optional web search, sign in to a VS Code Tunnel, or install updates. Model and software licenses vary.

## Install and run Ki

1. Install [Ollama for Windows](https://ollama.com/download/windows).
2. Download the default chat model:

   ```powershell
   ollama pull qwen3:0.6b
   ```

   For harder coding or reasoning tasks, optionally install:

   ```powershell
   ollama pull qwen3:1.7b
   ```

   For optional local image analysis, install Moondream when needed:

   ```powershell
   ollama pull moondream:latest
   ```

   Models are not downloaded automatically. The smaller Qwen model is generally faster on CPU; the larger model may provide better results but needs more time and memory. Ki serializes its managed model requests and limits CPU threads to reduce sustained laptop load.

3. Open a PowerShell terminal in the repository and install Ki’s Python dependencies:

   ```powershell
   python -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r requirements.txt
   ```

4. Start Ki by double-clicking `Ki.vbs`, or run it from the repository:

   ```powershell
   .\.venv\Scripts\python.exe main.py
   ```

The first use of voice input downloads and caches an English Vosk model. Enable microphone access for desktop apps in Windows Privacy settings if you use a microphone. Ambient listening is off until you explicitly enable it.

## Use Tot with Ki

### Pair the phone

1. Put the PC and phone on the same trusted Wi-Fi network, or connect both to the same private [Tailscale](https://tailscale.com/) network.
2. In Ki, open **Dashboard → Device & activity → Phone companion** and enable phone access.
3. In Tot, choose **Scan PC pairing QR** and scan the QR code shown by Ki. You can also enter the private address and pairing token manually.
4. Tap **Test connection** in Tot.

Ki’s phone bridge is disabled by default and binds to private network or Tailscale addresses. The PC must be awake with Ki running for Tot’s chat and in-app project browser to work.

### Browse and edit Projects

From Tot, choose **Workspace & connections → Browse Projects in Tot**. Open an existing supported text file, make edits, tap **Review diff**, then choose **Save reviewed diff** only if the changes look right. Tot supports UTF-8 text files up to 256 KB and does not create or delete files.

The in-app browser uses Ki’s paired phone connection and is limited to `%USERPROFILE%\Projects`. It excludes Git metadata, symbolic links, most hidden files, and unsupported file types. Files changed on the PC since they were opened must be reloaded before saving.

### Open a PC terminal with Termux

1. Install [Termux](https://f-droid.org/packages/com.termux/).
2. In Ki’s phone companion dashboard, install Windows **OpenSSH Server** if it is missing, then start the server.
3. In Tot, choose **Workspace & connections → Termux SSH**. Scan Ki’s separate SSH QR, review the connection details, then choose **Copy command & open Termux**.
4. Paste and run the copied command in Termux. It connects to the PC and starts PowerShell in `%USERPROFILE%\Projects`.

The Projects directory is the starting location, **not an SSH sandbox**. SSH runs with your Windows account’s normal file permissions. Keep SSH on a trusted local network or private Tailscale network; never expose it with a router port-forward.

### Open the full VS Code editor

VS Code Tunnel provides a graphical editor in the phone browser; it is separate from SSH and from Tot’s in-app editor.

1. Connect to the PC using Termux SSH and start a tunnel:

   ```powershell
   code tunnel --accept-server-license-terms --name TotLaptop
   ```

2. Complete the GitHub sign-in if prompted and leave the tunnel running. Copy only the URL beginning with `https://vscode.dev/tunnel/`.
3. In Tot, open **Workspace & connections → Remote VS Code**, paste the URL, and tap **Open in browser**.
4. Once connected, use VS Code Explorer to open a project folder on the PC.

For persistent access, VS Code documents `code tunnel service install`; the PC must still be powered on, awake, and online. VS Code Tunnel runs with your Windows account’s permissions and can access files beyond `Projects`. The first connection may require signing in to the same GitHub or Microsoft account authorized for the tunnel.

### Use the local OpenCode agent

OpenCode is installed separately from Ki. This project’s `opencode.json` defaults to `ollama/qwen3:0.6b`; choose `ollama/qwen3:1.7b` from OpenCode’s `/models` menu for more demanding coding tasks. The project configuration restricts providers to local Ollama, blocks web search, and requires approval for file access, edits, shell commands, or folders outside the project.

To view sessions in Tot, enable Ki’s **OpenCode monitor** in the dashboard and restart OpenCode so its plugin loads. Monitoring is optional and off by default. Ki keeps the project’s full path on the PC; Tot sees session and project labels, activity, messages, and pending permission requests.

### OpenCode sessions and Git on Tot

On the PC, enable Ki’s **OpenCode monitor** and **Phone companion**, then pair Tot over trusted Wi-Fi or Tailscale. Open **Agents** in Tot to view sessions and messages, respond to a session, and review pending permission requests. A permission can be allowed once or denied; Tot does not remember or automatically grant approvals.

Tot’s Git controls are limited to the verified repository associated with the selected OpenCode session. Status and diff are read-only. Stage, commit, pull, and push require confirmation. Commits use staged changes; pull is fast-forward-only and refuses to run when there are local changes; push uses the configured upstream.

Optional background permission alerts are off by default. Android checks periodically when network access is available; battery restrictions, an offline PC, or disconnected Tailscale can delay notifications. Tapping an alert opens the request for review and does not approve it.

OpenCode response speed depends mainly on local Ollama inference and the selected model. Qwen3:0.6b is the faster default; Qwen3:1.7b may handle harder tasks better but is slower on modest CPUs. Tot reduces polling while the Agents panel is closed. CPU thread limits help reduce sustained load but cannot guarantee a laptop temperature.

## Privacy and security

- Chat and local model inference use Ollama on the PC; Ki does not call a cloud AI API for chat.
- Optional web search sends your search query to the search provider.
- Photos stay on the phone unless you choose Google Lens or explicitly ask Ki to analyze an image. Local image analysis sends the selected image to Ollama on your PC.
- Ki reads VS Code clipboard text only after you press **Code**. Tot’s copied-selection action is also explicit.
- Project edits require diff review and approval. Ki does not delete project files or run arbitrary terminal commands.
- The phone bridge uses token-authenticated HTTP, which is not encrypted by itself. Use it only on a trusted private network; use Tailscale for encrypted access away from home. Do not port-forward the bridge port or expose it to the public internet.
- OpenSSH and VS Code Tunnel are separate services and follow the Windows account’s permissions. Protect your Windows and GitHub/Microsoft accounts and stop services you no longer need.

## Build and test

### Windows app

Install the requirements as described above, then run:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests
```

### Android app

With JDK 17 and Android SDK Platform 35 installed, build the debug APK from the repository root:

```powershell
.\android\gradlew.bat --no-daemon -p android :app:assembleDebug
```

The APK is written to `android\app\build\outputs\apk\debug\app-debug.apk`. Android Studio can also open the `android` directory as a project.

## Publish an Android release

The [Android release workflow](.github/workflows/android-release.yml) builds and publishes a signed `TotCompanion.apk` when a version tag matching `android-v*` is pushed. The workflow requires these GitHub Actions repository secrets:

- `KI_RELEASE_KEYSTORE_BASE64`
- `KI_RELEASE_STORE_PASSWORD`
- `KI_RELEASE_KEY_ALIAS`
- `KI_RELEASE_KEY_PASSWORD`

The signing keystore must remain private and must be backed up securely. Do not commit the keystore or passwords. Android accepts an in-place app update only when it is signed with the same key as the installed release. A debug-signed APK has a different signing key and cannot be updated directly by the GitHub-signed release.

After preparing a new version and confirming the signing secrets are configured, create and push a new tag:

```powershell
git tag android-v0.1.15
git push origin android-v0.1.15
```

This is an example; replace it with the next unused version tag. GitHub Actions builds the release APK and publishes it to the matching GitHub Release.

## Repository layout

```text
.
├── .github/workflows/       # Android release automation
├── .opencode/plugins/       # OpenCode integration plugins
├── android/                 # Native Android companion (Tot)
├── ki/                      # Windows desktop app and services
├── tests/                   # Python unit tests
├── Ki.vbs                   # Launch Ki without a terminal
├── main.py                  # Windows application entry point
├── opencode.json            # Project-local OpenCode settings
└── requirements.txt         # Python dependencies
```

Generated build output, Python virtual environments, caches, downloaded models, local configuration, and signing keys are excluded from Git.
