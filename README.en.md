# wxcode

WeChat Mini Program Login Hook Module (Xposed)

## Features

- **Mini Program Login** - Get WeChat mini program login code via HTTP API
- **Multi-Account Support** - Support WeChat multi-instance and system clone, auto port allocation
- **Background Keep-Alive** - Foreground Service to boost process priority, avoid background network throttling
- **Dual Mode Switching** - Performance mode (instant response) / Power saver mode (low power consumption)
- **Version Compatibility** - Support WeChat 8.0.49 ~ 8.0.76

## Requirements

- Android 4.2+ (API 17+)
- Xposed/LSPosed/EdXposed framework installed
- WeChat version: 8.0.49 / 8.0.62 / 8.0.70 / 8.0.71 / 8.0.72 / 8.0.74 / 8.0.76

## Installation

1. Build the APK from source, or download pre-built version from Releases
2. Install the APK on your phone
3. Enable this module in Xposed/LSPosed manager, select WeChat as the scope
4. Restart WeChat (or reboot your phone)

## Usage

### Basic Usage

The module automatically starts an HTTP server when WeChat launches:

- **Primary User WeChat**: `http://127.0.0.1:8088`
- **Cloned WeChat**: Port = 8088 + User ID (e.g., User 10 → 8098)

Visit `http://127.0.0.1:8088` in browser to view instance info and version compatibility.

### API Endpoints

| Endpoint | Description | Example |
|----------|-------------|---------|
| `/login` | Execute login and get code | `/login?appId=wxaa3a999db5d744c6` |
| `/whoami` | Return current instance info | `/whoami` |
| `/instances` | Return all running instances | `/instances` |
| `/config` | View/switch keep-alive mode | `/config?mode=power_saver` |

### Keep-Alive Modes

| Mode | Feature | Use Case |
|------|---------|----------|
| **Performance** | WakeLock always held, instant response | Need real-time response, acceptable battery drain |
| **Power Saver** | AlarmManager periodic wake-up | Long-term background running, 2-9 min delay acceptable |

> **Performance Mode** requires setting WeChat to "Unrestricted/Allow background activity" in system settings, otherwise network will still be throttled.

### Port Calculation

```
Base port: 8088
User ID ≤ 100: Port = 8088 + User ID
User ID > 100: Port = 8200 + (User ID % 100)
```

## Technical Architecture

```
├── app/src/main/
│   ├── assets/xposed_init          # Xposed entry class declaration
│   └── java/xiaojw/hook/
│       └── WxLoginHook.java        # Core hook logic
│           ├── HTTP Server         # NanoHTTPD embedded server
│           ├── KeepAliveService    # Foreground keep-alive service
│           └── Login Hook          # WeChat login hook
└── libs/api-82.jar                 # Xposed API
```

### Core Principles

1. Hook WeChat `JsApiLogin$LoginTask` related classes
2. Set login parameters via reflection and trigger login
3. Poll to get login result code
4. Provide service through HTTP API

## FAQ

**Q: "Login timeout" error?**
A: Check if WeChat is running in foreground, or switch to performance mode and grant WeChat unrestricted background access.

**Q: Clone WeChat not working?**
A: Make sure all clone instances have the module enabled in scope, visit `/instances` endpoint to confirm registration.

**Q: How to check current port?**
A: Visit `http://127.0.0.1:8088/whoami` to view current instance info.

## Disclaimer

This project is for educational and research purposes only. Do not use it for illegal purposes. Users are solely responsible for any consequences resulting from the use of this module.

## License

MIT License

## Contributing

1. Fork this repository
2. Create a Feat_xxx branch
3. Commit your code
4. Create a Pull Request