# AccessNav Hybrid — Google Hackathon

> **Phone senses. Cloud infers. Watch delivers guidance. NuttX validates execution.**

A technical accessibility navigation platform for blind and visually impaired users, combining mobile perception, cloud-based route reasoning, and low-latency wearable feedback for real-time situational awareness.

Built in 24 hours at the **Google Hackathon — Accessibility Track**.

---

## The Problem

Blind and visually impaired users encounter persistent mobility constraints that are often invisible to sighted users — inconsistent environmental semantics, incomplete spatial context, and delayed access to actionable wayfinding information.

Concrete daily obstacles include:

- Unmarked stairs, curb cuts, ramps, and elevation changes that require real-time obstacle classification and depth-aware hazard detection
- Emergency exits, egress paths, and compliance signage that are difficult to localize without multimodal scene understanding and text recognition
- Limited immediate visual feedback from the environment, reducing situational awareness and increasing reliance on low-latency perception pipelines
- Complex public-space navigation involving dynamic obstacles, ambiguous geometry, and rapidly changing pedestrian traffic patterns

---

## Our Solution

A multimodal assistive navigation architecture that integrates mobile sensing, cloud-based computer vision and reasoning, wearable haptic output, and RTOS-managed infrastructure. The system combines camera input, AI inference, and real-time feedback loops to deliver robust spatial guidance across heterogeneous devices and connectivity conditions.

---

## How It Works

```
CameraX (RGB frames)
        |
        v
ML Kit OCR  ------------------------------------------------------------->  On-device text detection
        |
        v
Gemini 2.5 Flash  (scene understanding + navigation reasoning)
        |
        +------------------------------------------------------------->  TTS Engine (voice output)
        |
        +------------------------------------------------------------->  Wearable Data Layer API
                                                                                |
                                                                                v
                                                                       Wear OS Watch
                                                                    (haptic actuator)
                                                                 left / right / stop / danger

         +-------------------------+
         |    NuttX BLE Beacon     |
         |  (fixed infrastructure) |
         |  ramp / stairs / exit   |
         +------------+------------+
                      |  BLE advertisement
                      v
              Phone receives local
              context and merges
              with Gemini output
```

### Pipeline Steps

1. **Capture** — CameraX runs a continuous low-latency image acquisition pipeline, streaming frames with adaptive exposure control and orientation handling.
2. **On-Device OCR** — ML Kit performs on-device text detection and optical character recognition, extracting structured text tokens from each frame with minimal network dependency.
3. **AI Analysis** — Gemini 2.5 Flash processes OCR output together with visual context to infer scene semantics, resolve ambiguity, and generate navigation-relevant guidance through multimodal reasoning.
4. **Output** — The system synthesizes speech via TTS and delivers directional cues through smartwatch haptics, creating a redundant multimodal feedback loop for accessibility and robustness.

---

## Demo Flow

| Step | Phase | Description |
|------|-------|-------------|
| 1 | Sensor Ingestion | User launches the app; CameraX streams live frames optimized for low-latency preview, frame throttling, and stable orientation handling |
| 2 | On-Device Interpretation | ML Kit OCR performs real-time text extraction; Gemini 2.5 Flash evaluates the visual scene without relying on constant network round-trips |
| 3 | Guidance Synthesis | OCR output, scene understanding, and navigation intent are fused into spoken instructions and haptic events via the Android audio stack and Wear OS vibration channels |
| 4 | Closed-Loop Navigation | Continuous guidance is maintained through iterative perception-feedback cycles; vibration patterns encode direction changes while voice prompts provide actionable spatial instructions |

---

## Features

### On-Device Vision Capture
The smartphone camera continuously acquires RGB video frames and performs lightweight edge-side preprocessing — frame normalization, stabilization, and region-of-interest selection — before transmission.

### Cloud AI Scene Understanding
Gemini 2.5 Flash executes multimodal inference on uploaded imagery, combining object detection, scene classification, and contextual reasoning to generate structured navigation cues with low-latency response behavior.

### Haptic Guidance Layer
The smartwatch receives semantic navigation events and converts them into tactile output patterns using vibration intensity, cadence, and directional signaling — an accessible feedback channel with minimal cognitive load.

### NuttX Beacon Infrastructure
Embedded RTOS-based beacon nodes provide local confirmation and proximity awareness through deterministic low-power wireless signaling, enabling resilient edge augmentation and high-precision environmental anchoring.

### Real-Time Outdoor Navigation
Integrates Google Maps routing, GPS telemetry, and live computer vision inference to produce low-latency turn-by-turn guidance. The navigation layer continuously reconciles map-matched position updates with scene understanding signals to maintain route continuity under variable visibility and urban complexity.

### Intelligent Hazard Detection
Uses an on-device or edge-assisted perception pipeline to identify curb edges, obstacles, signage, and atypical path conditions through object detection and scene classification. Detected hazards are converted into prioritized alerts for immediate spoken and haptic warnings.

### Environmental Data Fusion
Fuses street-level imagery, map metadata, GPS coordinates, and contextual route attributes into a unified representation for decision-making, enabling more accurate interpretation of intersections, crossings, landmarks, and local environmental constraints.

### Dynamic Route Guidance
Continuously recalculates guidance using real-time state estimation, route re-ranking, and context-aware decision logic when traffic patterns, camera observations, or positional drift change.

### Offline-First Architecture
OCR detection and haptic guidance work without a network connection. Cloud reasoning improves experience quality but is never a hard dependency.

### Zero-UI Interaction Paradigm
The user never touches the screen during navigation. Everything is communicated through voice and vibration — an interaction model built from the ground up for blind users, not retrofitted from a sighted interface.

---

## Technical Stack

| Layer | Technology | Details |
|-------|------------|---------|
| Mobile | Android + Kotlin | Jetpack Compose, CameraX, coroutine-driven async processing |
| Vision | ML Kit OCR | Text Recognition v2, on-device, minimal network dependency |
| AI Cloud | Gemini 2.5 Flash | Multimodal inference, Google AI SDK for Android |
| Wearable | Wear OS | Wearable Data Layer API, haptic vibration patterns |
| Audio | Android TTS | TextToSpeech Engine with urgency prioritization |
| Beacon / RTOS | NuttX RTOS | BLE advertisement, deterministic task scheduling, low-jitter coordination |

---

## Key Innovations

**Multimodal accessibility without proprietary hardware**
The first system combining local OCR, generative AI, and wrist haptics in a single coherent flow for blind users — running entirely on consumer off-the-shelf devices.

**RTOS-based edge control plane**
NuttX brings an ultra-fast local context layer — faster than cloud, more precise than GPS — through fixed BLE beacons inside buildings where GPS is unreliable or unavailable. Deterministic task scheduling ensures predictable latency at the edge.

**Offline resilience**
Detection and haptics work without any network connection. Cloud reasoning improves experience quality but is never a hard dependency.

**Zero-UI interaction model**
Built from the ground up for non-visual use. No screen interaction during navigation. Voice and vibration are the primary output channels, not accessibility afterthoughts.

**Low-latency guidance pipeline**
End-to-end streaming architecture for sensor ingestion, spatial inference, and instruction generation, optimizing path updates and feedback delivery for near-instant route correction.

---

## Real-World Applications

- Public buildings and offices with indoor wayfinding constraints, dynamic layouts, and multiple points of interest
- Airports and transit stations requiring high-throughput navigation, time-sensitive routing, and robust signage interpretation
- Emergency situations where rapid re-routing, obstacle awareness, and fallback guidance are critical
- Events and temporary venues with changing floor plans, transient barriers, and incomplete infrastructure data

---

## Social Impact

AccessNav Hybrid improves the independence, situational awareness, and decision-making confidence of blind and low-vision users by combining multimodal feedback, low-latency guidance, and context-aware navigation in public environments. The system translates sensor input, map context, and route state into actionable cues, reducing cognitive load and enabling safer autonomous movement across complex spaces.

---

## Future Plans

**Indoor navigation scaling** — fusion of spatial maps, beacon infrastructure, and localization pipelines including sensor fusion with BLE, Wi-Fi RTT, and computer vision-based pose estimation.

**Smart city integration** — interoperability with connected urban systems, digital twins, and open mobility APIs for real-time infrastructure awareness.

**More precise obstacle detection** — additional depth sensors, temporal filtering, and advanced AI perception models for improved segmentation, classification, and trajectory prediction.

---

## Getting Started

### Prerequisites

- Android device (API 26+)
- Paired Wear OS watch
- Android Studio (latest stable)
- A [Gemini API key](https://aistudio.google.com/)

### Installation

```bash
git clone https://github.com/your-team/accessnav-hybrid
cd accessnav-hybrid
```

Open the project in Android Studio. Add your Gemini API key to `local.properties`:

```properties
GEMINI_API_KEY=your_key_here
```

### Running the App

1. Connect your Android phone via USB or Wi-Fi debugging.
2. Ensure your Wear OS watch is paired and connected.
3. Run the `app` module on the phone.
4. Run the `wear` module on the watch.

Navigation starts automatically — no screen interaction required.

---

## License

This project was built at the Google Hackathon — Accessibility Track.
