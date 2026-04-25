# AccessNav Hybrid

> Phone sees. Cloud reasons. Watch guides. NuttX confirms.

An accessibility-first navigation assistant for blind and visually impaired users, combining on-device vision, generative AI, Wear OS haptics, and NuttX smart infrastructure beacons into a single seamless guidance system.

Built in 24 hours at the Google Hackathon — Accessibility Track.

---

## The Problem

Blind and visually impaired people navigate environments designed entirely for sighted users. Unmarked stairs, hard-to-find emergency exits, lifts with no audio feedback — these are daily obstacles that most people never notice.

AccessNav Hybrid turns an Android phone and a Wear OS watch into a real-time tactile and vocal guide, with no proprietary hardware and no dependency on specialized infrastructure.

---

## How It Works

```
Camera (ML Kit OCR)
       |
       v
Gemini 1.5 Flash  ---->  Text-to-Speech (phone)
       |
       v
NuttX BLE Beacon  ---->  Haptic commands (Wear OS watch)
```

1. The phone camera continuously scans the environment.
2. ML Kit detects signs: EXIT, STAIRS, LIFT, RAMP.
3. Gemini interprets the full scene and generates a short, safe instruction.
4. The instruction is read aloud via Text-to-Speech.
5. A nearby NuttX beacon provides local context: obstacle type, distance, alternative route.
6. The Wear OS watch delivers directional haptic commands — left, right, stop, danger — without requiring any visual or additional auditory attention.

---

## Demo Flow

| Step | Event | Output |
|------|-------|--------|
| 1 | User starts navigation | Camera activates, continuous scanning begins |
| 2 | ML Kit detects: STAIRS | Frame sent to Gemini for scene interpretation |
| 3 | Gemini responds | "Stairs ahead. Turn left for the accessible ramp." spoken aloud |
| 4 | NuttX beacon confirms | Local context: ramp distance 4m, direction confirmed |
| 5 | Wear OS fires haptics | Short pulses to the left — directional confirmation on the wrist |

---

## Features

### On-Device Vision
ML Kit Text Recognition v2 reads environmental signs in real time, directly from the camera stream, with no internet required.

### Generative Scene Understanding
Gemini 1.5 Flash analyzes the full camera frame and generates short, context-aware safety instructions tailored to the user's immediate environment.

### Adaptive Text-to-Speech
Instructions are spoken aloud with automatic urgency prioritization. The system distinguishes between informational guidance and immediate hazard alerts.

### Wear OS Haptic Navigation
The watch receives directional haptic commands over the Wearable Data Layer API. The user never needs to look at a screen or listen to audio to receive navigation cues.

### NuttX Smart Beacons
NuttX RTOS microcontrollers simulate fixed BLE beacons at key locations: accessible entrances, ramps, stairs, emergency exits. They provide ultra-low-latency local context faster than GPS and more precise than cloud inference alone.

### Offline-First Architecture
OCR detection and haptic guidance work without a network connection. Gemini enhances the experience but never blocks it.

### Zero-UI Interaction Paradigm
The user never touches the screen during navigation. Everything is communicated through voice and vibration — an interaction model built from the ground up for blind users, not retrofitted from a sighted interface.

---

## Technical Stack

| Layer | Technology | Details |
|-------|-----------|---------|
| Mobile | Android + Kotlin | Jetpack Compose, CameraX |
| Vision | ML Kit OCR | Text Recognition v2, on-device |
| AI Cloud | Gemini 1.5 Flash | Google AI SDK for Android |
| Wearable | Wear OS | Wearable Data Layer API |
| Audio | Android TTS | TextToSpeech Engine |
| Beacon / RTOS | NuttX RTOS | BLE advertisement simulation |

---

## Key Innovations

**Multimodal accessibility without proprietary hardware**
The first system combining local OCR, generative AI, and wrist haptics in a single coherent flow for blind users, running on consumer off-the-shelf devices.

**RTOS infrastructure layer**
NuttX brings an ultra-fast local context layer — faster than cloud, more precise than GPS — through fixed BLE beacons inside buildings where GPS is unreliable or unavailable.

**Offline resilience**
Detection and haptics work without any network. Cloud reasoning improves experience quality but is never a hard dependency.

**Zero-UI interaction model**
Built from the ground up for non-visual use. No screen interaction during navigation. Voice and vibration are the primary output channels, not accessibility afterthoughts.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Android Phone                         │
│                                                         │
│   CameraX  ──>  ML Kit OCR  ──>  Sign Detected         │
│                                        │                │
│                                        v                │
│                               Gemini 1.5 Flash          │
│                               (scene interpretation)    │
│                                        │                │
│                          ┌─────────────┴──────────┐     │
│                          v                        v     │
│                    TTS Engine              Wearable API │
│                  (voice output)                   │     │
└──────────────────────────────────────────────────-│─────┘
                                                    │
                                      ┌─────────────v──────┐
                                      │    Wear OS Watch   │
                                      │  Haptic Actuator   │
                                      │  left / right /    │
                                      │  stop / danger     │
                                      └────────────────────┘

         ┌─────────────────────────┐
         │   NuttX BLE Beacon      │
         │   (fixed infrastructure)│
         │   ramp / stairs / exit  │
         └────────────┬────────────┘
                      │ BLE advertisement
                      v
              Phone receives local
              context and merges
              with Gemini output
```

---

## Getting Started

```bash
git clone https://github.com/your-team/accessnav-hybrid
cd accessnav-hybrid
```

Open in Android Studio. Connect an Android device and a paired Wear OS watch. Add your Gemini API key to `local.properties`:

```
GEMINI_API_KEY=your_key_here
```

Run the `app` module on the phone and the `wear` module on the watch.

---

## Team

Built in 24 hours at the Google Hackathon — Accessibility Track.

---

## License

MIT
