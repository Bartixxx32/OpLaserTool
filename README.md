# OpLaserTool 🔴📏

**OpLaserTool** is a professional, open-source laser rangefinder application designed specifically for **OnePlus** devices equipped with laser autofocus hardware (e.g., OnePlus 7 Pro, 8 Pro).

![Build Status](https://github.com/Bartixxx32/OpLaserTool/actions/workflows/android.yml/badge.svg)

## Features ✨
- **Precision Measurement**: Access raw sensor data directly from `/dev/input/event11`.
- **Status Monitoring**: Real-time signal quality indicators (Green/Yellow/Red).
- **Watchdog Protection**: Auto-detection of "Out of Range" / Sensor Silence (>500ms).
- **Advanced UI**: Dark mode, Haptic Feedback (custom patterns), and Dynamic visual cues.
- **Smart Filtering**: Moving Average (N=10) smoothing and error suppression logic.

## Compatibility 📱
- **Primary Target**: OnePlus 7 Pro / 8 Pro (Devices with STMicro Laser AF).
- **Requirement**: Root Access (to read `/dev/input/` events).

## Installation 📦
Download the latest signed APK from the [Releases](https://github.com/Bartixxx32/OpLaserTool/releases) page.

## License 📄
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
