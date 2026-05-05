# Refactoring Plan: ha-bridge → Go

## Motivation

The current Java implementation has significant drawbacks for resource-constrained Linux targets like DietPi:

| Criteria | Java (current) | Go (proposed) |
|---|---|---|
| RAM usage | ~150–300 MB (JVM overhead) | ~10–20 MB |
| Deployment | JAR + JRE installation required | Single static binary, zero dependencies |
| DietPi suitability | Poor (JVM startup, memory) | Excellent |
| ARM build | JRE must be installed | `GOOS=linux GOARCH=arm64 go build` |
| Startup time | 3–8 seconds | <100 ms |
| Binary size | JRE ~200 MB | ~10 MB binary |

**Goals:**
- No Java / JVM requirement
- Runs on Linux (DietPi / Raspberry Pi / Orange Pi)
- No backwards compatibility — clean slate
- One-shot import tool for existing `devices.db` configs

---

## What stays, what goes

### Kept
- UPnP/SSDP discovery (Alexa device detection)
- Philips Hue API emulation (`/api/{userId}/lights`, `/api/{userId}/groups`)
- Device CRUD REST API
- Executors: HTTP/HTTPS, MQTT, TCP, UDP, Shell/Script
- Web UI (embedded in binary via Go `embed`)
- Variable substitution (`${intensity.percent}`, `${color.r}`, etc.)
- Config import from old `devices.db`

### Removed
- All platform-specific handlers (Vera, Fibaro, Harmony Hub, LIFX, Nest, Domoticz, etc.)
  — Modern systems (Home Assistant, OpenHAB 3+, etc.) all expose HTTP/MQTT APIs; direct calls suffice
- XMPP / Smack library
- Complex authentication / Google Guice DI
- Hue Groups complexity (Alexa scenes handle grouping)

---

## Project Structure

```
ha-bridge/
├── cmd/ha-bridge/
│   └── main.go                 # Entry point, flag parsing, service wiring
├── internal/
│   ├── bridge/
│   │   ├── bridge.go           # HTTP server init, graceful shutdown
│   │   └── config.go           # YAML config loading, defaults
│   ├── hue/
│   │   ├── api.go              # Hue REST endpoints (/api/{userId}/...)
│   │   └── state.go            # In-memory device state (on/off/brightness/color)
│   ├── upnp/
│   │   └── listener.go         # SSDP UDP multicast, M-SEARCH response
│   ├── device/
│   │   ├── manager.go          # Device registry, dispatch to executors
│   │   ├── store.go            # JSON persistence (one file per device)
│   │   └── model.go            # Device / Action types
│   ├── executor/
│   │   ├── executor.go         # Interface + variable substitution engine
│   │   ├── http.go             # HTTP/HTTPS with header and body support
│   │   ├── mqtt.go             # MQTT publish (paho)
│   │   ├── tcp.go              # Raw TCP send
│   │   ├── udp.go              # Raw UDP send
│   │   └── exec.go             # Shell command execution
│   ├── api/
│   │   └── handler.go          # Management REST API (/api/devices CRUD)
│   └── migrate/
│       └── importer.go         # Import old ha-bridge devices.db → new format
└── web/                        # Embedded frontend (Go embed.FS)
    ├── index.html
    └── app.js
```

---

## Device Format (new)

Each device is stored as a single JSON file under `data_dir/`.

```json
{
  "id": "550e8400-e29b-41d4-a716",
  "name": "Living Room Light",
  "type": "switch",
  "on_action": {
    "type": "http",
    "method": "POST",
    "url": "http://homeassistant.local:8123/api/services/light/turn_on",
    "headers": { "Authorization": "Bearer ${HA_TOKEN}", "Content-Type": "application/json" },
    "body": "{\"entity_id\": \"light.living_room\"}"
  },
  "off_action": {
    "type": "http",
    "method": "POST",
    "url": "http://homeassistant.local:8123/api/services/light/turn_off",
    "headers": { "Authorization": "Bearer ${HA_TOKEN}", "Content-Type": "application/json" },
    "body": "{\"entity_id\": \"light.living_room\"}"
  },
  "dim_action": {
    "type": "http",
    "method": "POST",
    "url": "http://homeassistant.local:8123/api/services/light/turn_on",
    "headers": { "Authorization": "Bearer ${HA_TOKEN}", "Content-Type": "application/json" },
    "body": "{\"entity_id\": \"light.living_room\", \"brightness_pct\": ${intensity.percent}}"
  }
}
```

**Supported executor types:** `http`, `mqtt`, `tcp`, `udp`, `exec`

**Variable substitution (same as original):**
- `${intensity.percent}` — brightness 0–100
- `${intensity.byte}` — brightness 0–255
- `${color.r}`, `${color.g}`, `${color.b}` — RGB values
- `${color.hue}`, `${color.saturation}` — HSB values
- `${device.name}` — device name
- `${time.format(HH:mm:ss)}` — current time

---

## Configuration

```yaml
# /etc/ha-bridge/config.yaml

server:
  port: 80
  ip: ""              # empty = auto-detect primary interface

upnp:
  name: "HA Bridge"
  uuid: ""            # empty = auto-generated on first start, then persisted

mqtt:
  broker: ""          # e.g. tcp://localhost:1883
  username: ""
  password: ""

data_dir: "/etc/ha-bridge/devices"
```

---

## Migration / Import

```bash
ha-bridge import \
  --from /path/to/old/devices.db \
  --out  /etc/ha-bridge/devices/
```

**Mapping logic:**

| Old type | New type | Notes |
|---|---|---|
| `httpDevice` | `http` | 1:1 mapping |
| `mqttDevice` | `mqtt` | 1:1 mapping |
| `tcpDevice` | `tcp` | 1:1 mapping |
| `udpDevice` | `udp` | 1:1 mapping |
| `execDevice` | `exec` | 1:1 mapping |
| `veraDevice` | `http` | URL reconstructed from Vera API; manual review needed |
| `harmonyDevice` | `http` | Harmony HTTP API URL; manual review needed |
| other platform | `http` | Placeholder URL with `# TODO` comment in name |

Output: one `{uuid}.json` per device, import summary printed to stdout.

---

## DietPi Deployment

### Build (on dev machine)

```bash
# For Raspberry Pi 4 / Orange Pi (64-bit ARM)
GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o ha-bridge ./cmd/ha-bridge

# For older Raspberry Pi / 32-bit ARM
GOOS=linux GOARCH=arm GOARM=7 go build -ldflags="-s -w" -o ha-bridge ./cmd/ha-bridge
```

### Install on DietPi

```bash
sudo cp ha-bridge /usr/local/bin/
sudo useradd -r -s /bin/false ha-bridge
sudo mkdir -p /etc/ha-bridge/devices
sudo chown -R ha-bridge:ha-bridge /etc/ha-bridge
sudo cp config.yaml /etc/ha-bridge/
```

### systemd Service

```ini
# /etc/systemd/system/ha-bridge.service
[Unit]
Description=HA Bridge (Hue Emulator)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/ha-bridge --config /etc/ha-bridge/config.yaml
Restart=always
RestartSec=5
User=ha-bridge
Group=ha-bridge
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ha-bridge
```

---

## Key Dependencies (Go modules)

| Package | Purpose |
|---|---|
| `github.com/go-chi/chi/v5` | HTTP router |
| `github.com/eclipse/paho.mqtt.golang` | MQTT client |
| `github.com/google/uuid` | UUID generation |
| `gopkg.in/yaml.v3` | YAML config parsing |
| standard `net/http` | HTTP server |
| standard `embed` | Embed web UI into binary |

---

## Implementation Phases

| Phase | Scope | Estimated effort |
|---|---|---|
| 1 | UPnP/SSDP + Hue API skeleton (Alexa discovers bridge) | 2–3 days |
| 2 | Device model, JSON store, management REST API | 1–2 days |
| 3 | Executors: HTTP, MQTT, TCP, UDP, exec | 1–2 days |
| 4 | Web UI (device list, add/edit/delete) | 1–2 days |
| 5 | Migration / import tool | 0.5 days |
| 6 | DietPi testing, systemd integration | 0.5–1 day |
| **Total** | | **~7–10 days** |

Phase 1 is the highest-risk item — the SSDP multicast responses and Hue API response format must exactly match what Alexa expects.
