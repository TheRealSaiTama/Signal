# Signal App Overview

## Description
A modern Android audio‑processing application built with Kotlin and Jetpack Compose. It provides a sleek workbench UI with a glowing animated border, logo‑centered status panel, and state‑driven audio pipeline (Idle → Source Loaded → DSP Processing → Completed).

## Key Features
- **Premium UI**: glassmorphic glowing border, dynamic waveforms, centered logo.
- **State Management**: `StateFlow`‑driven `WorkbenchState` handling idle, source loaded, processing, completed, error.
- **Audio Workflow**: pick media source, run DSP, export cleaned audio.
- **Extensible Architecture**: clean separation of UI (`WorkbenchScreen.kt`), ViewModel, and audio engine.

## Folder Structure
```
signal/
├─ app/src/main/java/com/example/ui/WorkbenchScreen.kt   # Main UI screen
├─ app/src/main/java/com/example/viewmodel/SignalViewModel.kt   # Business logic & state
├─ app/src/main/res/drawable/app_logo.png   # App logo asset
├─ documents/   # ← This folder contains all Obsidian documentation files
│   ├─ Overview.md
│   ├─ Architecture.md
│   ├─ UI.md
│   ├─ Build.md
│   ├─ Assets.md
│   └─ Usage.md
└─ ...
```
