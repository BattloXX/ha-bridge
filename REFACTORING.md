# EchoLox — Refactoring Plan (LoxBerry Plugin)

> Nachfolger von EchoLox. Neuer Name: **EchoLox**.  
> Repository: `BattloXX/EchoLox` (wird umbenannt nach `BattloXX/EchoLox`)

## Vision

EchoLox wird als **LoxBerry Plugin** entwickelt:  
Alexa spricht mit EchoLox (Hue-Emulation) → EchoLox sendet Kommandos direkt an den **Loxone Miniserver** (HTTP oder UDP) → Loxone steuert die echten Geräte.

Loxone bleibt die einzige Automations-Zentrale. EchoLox ist nur die Brücke zwischen Alexa und Loxone's Virtual Inputs.

```
Alexa
  │  Hue API (Port 8083)
  ▼
EchoLox (LoxBerry Plugin)
  │  HTTP GET  /dev/sps/io/{name}/{value}   (Basic Auth)
  │  oder UDP  {name}={value}\r\n
  ▼
Loxone Miniserver
  │  Virtual Inputs → Logik-Blöcke
  ▼
Echte Geräte (Lampen, Rolläden, Szenen, …)
```

---

## Motivation: Warum Go statt Java?

| Kriterium | Java (aktuell) | Go (neu) |
|---|---|---|
| RAM-Verbrauch | ~150–300 MB (JVM) | ~10–20 MB |
| Deployment | JAR + JRE installieren | Einzelnes Binary, keine Deps |
| DietPi / Raspberry Pi | schlecht (JVM-Overhead) | ausgezeichnet |
| ARM-Build | JRE muss installiert sein | `GOOS=linux GOARCH=arm64 go build` |
| Startup-Zeit | 3–8 Sekunden | <100 ms |

---

## Was bleibt, was fliegt

### Behalten
- UPnP/SSDP Discovery (Alexa-Erkennung)
- Philips Hue API-Emulation (`/api/{userId}/lights`, `/api/{userId}/groups`)
- Device CRUD REST-API
- Variable-Substitution (`${intensity.percent}`, `${color.r}`, etc.)
- Import-Tool für alte `devices.db`

### Weggeworfen
- Alle generischen Executors (HTTP zu Drittgeräten, MQTT, TCP, UDP zu Drittgeräten)
- Alle platform-spezifischen Handler (Vera, Fibaro, Harmony, LIFX, Nest, …)
- XMPP, Guice DI, komplexe Auth
- Generische Hue Groups

### Neu
- **Loxone-Transport**: HTTP und UDP direkt zum Miniserver
- **Auto Virtual-Input-Namen**: beim Anlegen eines Devices werden Loxone-Namen automatisch generiert
- **Loxone-Verifikation**: prüft ob Virtual Inputs im Miniserver existieren (wie MQTT Gateway Status-Ansicht)
- **MQTT-Gateway-Integration**: optionale Auto-Registrierung von Subscriptions im LoxBerry MQTT Gateway
- **Import-Web-UI**: alte `devices.db` hochladen, Mapping prüfen, übernehmen
- **LoxBerry Plugin-Paket**: korrekte Struktur mit Daemon, Install-Hooks, Web-Frontend

---

## Kommunikation mit dem Loxone Miniserver

### HTTP-Transport

Loxone Virtual HTTP Inputs akzeptieren GET-Requests:

```
GET http://{miniserver-ip}/dev/sps/io/{virtual_input_name}/{value}
Authorization: Basic base64(user:password)
```

Beispiel — Lampe einschalten:
```
GET http://192.168.1.7/dev/sps/io/ha_wohnzimmer_licht_on/1
```

Beispiel — Helligkeit:
```
GET http://192.168.1.7/dev/sps/io/ha_wohnzimmer_licht_brightness/75
```

### UDP-Transport

Loxone Virtual UDP Inputs empfangen UDP-Pakete:

```
{virtual_input_name}={value}\r\n
```

Ziel: `{miniserver-ip}:{udp-port}` (konfigurierbar, Default 7777)

### MQTT-Transport (via LoxBerry MQTT Gateway)

EchoLox published auf MQTT-Topics. Das bereits im LoxBerry integrierte MQTT Gateway leitet an den Miniserver weiter:

```
Topic:   ha_bridge/{device_name}/{property}
Payload: {value}
```

EchoLox registriert beim Speichern eines Devices automatisch die nötige Subscription im MQTT-Gateway-Config (`/opt/loxberry/config/plugins/mqttgateway/...`).

---

## Auto-Generierung von Virtual-Input-Namen

Beim Anlegen eines Devices werden automatisch alle nötigen Loxone Virtual Input Namen generiert.

### Namenssschema

```
ha_{device_name_normalized}_{property}
```

`device_name_normalized`: Kleinbuchstaben, Umlaute ersetzt, Sonderzeichen → Unterstrich

Beispiel: `"Wohnzimmer Licht"` → `wohnzimmer_licht`

### Virtual Inputs nach Device-Typ

| Device-Typ | Generierte Virtual Inputs | Wertebereich |
|---|---|---|
| `switch` | `ha_{name}_on` | `1` / `0` |
| `dimmer` | `ha_{name}_on`, `ha_{name}_brightness` | `1`/`0`, `0–100` |
| `color` | `ha_{name}_on`, `ha_{name}_brightness`, `ha_{name}_hue`, `ha_{name}_saturation` | diverse |
| `scene` | `ha_{name}_activate` | `1` (Puls) |

### Beispiel: Alexa sagt "Wohnzimmer Licht auf 60%"

```
Hue API → PUT /api/{user}/lights/{id}/state
         { "on": true, "bri": 153 }

EchoLox sendet:
  GET .../dev/sps/io/ha_wohnzimmer_licht_on/1
  GET .../dev/sps/io/ha_wohnzimmer_licht_brightness/60
```

---

## Loxone Virtual Input — Verifikation

Nach dem Anlegen eines Devices prüft EchoLox automatisch, ob die Virtual Inputs im Miniserver existieren. Dazu wird die Loxone Struktur-API abgefragt:

```
GET http://{miniserver}/data/LoxAPP3.json
```

Die Antwort enthält alle konfigurierten Blöcke inkl. Virtual Inputs. EchoLox vergleicht die generierten Namen mit den tatsächlich konfigurierten Inputs.

### Status-Anzeige (wie MQTT Gateway)

Die Web-UI zeigt pro Device eine Statuszeile — analog zur MQTT-Gateway "Incoming Overview":

| Status | Bedeutung |
|---|---|
| ✅ OK | Virtual Input existiert + hat kürzlich einen Wert empfangen |
| 🟠 Not found | Name im Miniserver nicht gefunden — Erinnerung zum manuellen Anlegen |
| 🔴 Access denied | Falsche Credentials für den Miniserver |
| ⬜ Not sent yet | Device noch nie ausgelöst worden |

Die Statusseite zeigt alle Virtual Inputs mit letztem Wert und Zeitstempel:

```
┌──────────────────────────────────────┬──────────────┬─────────────────┐
│ Loxone Virtual Input Name            │ Letzter Wert │ Zuletzt gesendet│
├──────────────────────────────────────┼──────────────┼─────────────────┤
│ ✅ ha_wohnzimmer_licht_on            │ 1            │ 05.05. 22:14:03 │
│ ✅ ha_wohnzimmer_licht_brightness    │ 60           │ 05.05. 22:14:03 │
│ 🟠 ha_schlafzimmer_decke_on          │ —            │ nie             │
│ ⬜ ha_terrasse_szene_activate        │ —            │ nie             │
└──────────────────────────────────────┴──────────────┴─────────────────┘
```

---

## Web-Oberfläche

Das Go-Binary bettet die gesamte Web-UI via `embed.FS` ein (kein externer Webserver nötig für die EchoLox-Verwaltung).

### Seiten

#### 1. Geräteübersicht (`/ui/`)
- Liste aller konfigurierten Devices
- Inline-Status (✅ / 🟠 / 🔴) für jeden Virtual Input
- Buttons: Bearbeiten, Löschen, Testen (einmaliger Puls)

#### 2. Gerät anlegen / bearbeiten (`/ui/device`)
- Felder: Name, Typ (switch / dimmer / color / scene)
- Automatisch generierte Virtual Input Namen werden live angezeigt
- Transport wählbar: HTTP / UDP / MQTT
- Testbutton: sendet sofort einen Wert an den Miniserver

#### 3. Loxone Status-Übersicht (`/ui/status`) — wie MQTT Gateway Screenshot
- Tabelle aller Virtual Inputs mit Status, letztem Wert, Zeitstempel
- Filter-Buttons: Alle / OK / Not found / Access denied / Not sent yet
- Suchfeld
- „Refresh"-Button → fragt Loxone-Struktur neu ab

#### 4. Einstellungen (`/ui/settings`)
- Miniserver IP/Hostname
- Benutzername + Passwort (Loxone)
- Transport-Standard: HTTP / UDP / MQTT
- UDP-Port (Default 7777)
- EchoLox Port (Default 8083)
- MQTT-Broker (wenn MQTT-Transport gewählt)
- Verbindungstest-Button

#### 5. Import (`/ui/import`) — Alte EchoLox Konfiguration
- Datei-Upload: `devices.db` (alte JSON-Datenbank)
- Vorschau-Tabelle: alter Name → neuer Virtual-Input-Name
- Warnungen für platform-spezifische Devices (Vera, Fibaro, …) — werden übersprungen
- „Importieren"-Button: übernimmt alle validen Devices
- Download-Link: exportiert die neue Konfiguration als JSON

---

## Import-Tool für alte Konfiguration

### Web-UI Upload-Flow

```
1. Benutzer öffnet /ui/import
2. Lädt devices.db hoch (Drag & Drop oder Datei-Dialog)
3. EchoLox parst die Datei und zeigt Vorschau:

   ┌───────────────────────────────┬──────────────────────────────┬────────┐
   │ Alter Name                    │ Neue Virtual Inputs           │ Status │
   ├───────────────────────────────┼──────────────────────────────┼────────┤
   │ Living Room Light (http)      │ ha_living_room_light_on      │ ✅ OK  │
   │                               │ ha_living_room_light_bright. │        │
   ├───────────────────────────────┼──────────────────────────────┼────────┤
   │ Vera Scene 1 (vera)           │ —                            │ ⚠️ Skip│
   └───────────────────────────────┴──────────────────────────────┴────────┘

4. Benutzer kann Namen manuell anpassen
5. Klick „Importieren" → Devices werden gespeichert
```

### CLI-Import (alternativ)

```bash
EchoLox import \
  --from /pfad/zur/alten/devices.db \
  --out  /opt/loxberry/data/plugins/EchoLox/devices/
```

### Mapping-Logik

| Alter Typ | Neues Device | Hinweis |
|---|---|---|
| `httpDevice` (on/off URL) | `switch` | Virtual Inputs: `_on` |
| `httpDevice` (mit dimUrl) | `dimmer` | Virtual Inputs: `_on`, `_brightness` |
| `mqttDevice` | `switch` / `dimmer` | je nach URLs |
| `execDevice` | `switch` | Exec-Logik entfällt — Loxone übernimmt |
| `tcpDevice` | `switch` | TCP-Logik entfällt |
| `veraDevice` | — | Übersprungen, Warnung |
| `harmonyDevice` | — | Übersprungen, Warnung |
| alle anderen Plattform-Typen | — | Übersprungen, Warnung |

---

## Projektstruktur (Go)

```
EchoLox/
├── cmd/EchoLox/
│   └── main.go                   # Entry point, flag parsing
├── internal/
│   ├── bridge/
│   │   ├── bridge.go             # HTTP-Server Init
│   │   └── config.go             # YAML-Config, LoxBerry Env-Vars
│   ├── hue/
│   │   ├── api.go                # Hue REST Endpoints
│   │   └── state.go              # Device State (on/bri/color)
│   ├── upnp/
│   │   └── listener.go           # SSDP UDP Multicast
│   ├── device/
│   │   ├── manager.go            # Device Registry
│   │   ├── store.go              # JSON Persistenz
│   │   ├── model.go              # Device / VirtualInput Typen
│   │   └── naming.go             # Auto-Namens-Generierung
│   ├── loxone/
│   │   ├── client.go             # HTTP + UDP Transport
│   │   ├── verify.go             # LoxAPP3.json Abfrage, Status-Check
│   │   └── mqttbridge.go         # MQTT-Gateway Config Auto-Registrierung
│   ├── api/
│   │   └── handler.go            # Management REST API (/api/devices)
│   ├── migrate/
│   │   └── importer.go           # devices.db → neues Format
│   └── web/
│       └── handler.go            # Web-UI Handler (/ui/...)
└── web/                          # Embedded Frontend (embed.FS)
    ├── index.html                # Geräteübersicht
    ├── device.html               # Anlegen / Bearbeiten
    ├── status.html               # Virtual Input Status
    ├── settings.html             # Einstellungen
    ├── import.html               # Import-UI
    └── assets/
        ├── app.js
        └── style.css
```

---

## Neues Device-Format (JSON)

```json
{
  "id": "550e8400-e29b-41d4-a716",
  "name": "Wohnzimmer Licht",
  "type": "dimmer",
  "virtual_inputs": {
    "on":         "ha_wohnzimmer_licht_on",
    "brightness": "ha_wohnzimmer_licht_brightness"
  },
  "transport": "http",
  "last_sent": {
    "ha_wohnzimmer_licht_on": { "value": "1", "at": "2026-05-05T22:14:03Z" },
    "ha_wohnzimmer_licht_brightness": { "value": "60", "at": "2026-05-05T22:14:03Z" }
  }
}
```

---

## Globaler LoxBerry Miniserver

EchoLox verwendet **keinen eigenen Miniserver-Konfigurationsblock**. Stattdessen wird die globale LoxBerry-Miniserver-Konfiguration gelesen:

```
/opt/loxberry/config/system/miniserver.json
```

Go-Code zum Einlesen:

```go
// internal/loxone/lbconfig.go

type LBMiniserver struct {
    Name      string `json:"Name"`
    IPAddress string `json:"IPAddress"`
    Port      string `json:"Port"`
    Admin     string `json:"Admin"`
    Pass      string `json:"Pass"`
}

func ReadLoxBerryMiniservers() (map[string]LBMiniserver, error) {
    // LBSCFG = /opt/loxberry/config/system
    path := filepath.Join(os.Getenv("LBSCFG"), "miniserver.json")
    data, err := os.ReadFile(path)
    if err != nil {
        return nil, err
    }
    var result struct {
        Miniserver map[string]LBMiniserver `json:"Miniserver"`
    }
    return result.Miniserver, json.Unmarshal(data, &result)
}
```

In den Einstellungen (`/ui/settings`) gibt es nur ein **Dropdown zur Miniserver-Auswahl** (relevant wenn mehrere Miniserver in LoxBerry konfiguriert sind). Credentials werden nie doppelt eingegeben.

---

## Konfiguration

```yaml
# /opt/loxberry/config/plugins/EchoLox/echolox.cfg

server:
  port: 8083
  ip: ""              # leer = auto-detect

upnp:
  name: "EchoLox"
  uuid: ""            # leer = auto-generiert, dann gespeichert

loxone:
  miniserver: "1"     # ID aus LoxBerry miniserver.json (Default: erster Eintrag)
  transport: "http"   # http | udp | mqtt
  udp_port: 7777

mqtt:
  broker: "tcp://localhost:1883"   # nur bei transport: mqtt
  username: ""
  password: ""

data_dir: ""          # leer = $LBPDATA aus LoxBerry-Umgebung
```

---

## LoxBerry Plugin-Paket

### Verzeichnisstruktur

```
EchoLox/                         ← ZIP-Root (Paketname)
├── plugin.cfg
├── preinstall.sh
├── postinstall.sh
├── preroot.sh
├── postroot.sh                    ← Architektur-Detection, Binary setzen
├── postupgrade.sh
├── bin/
│   ├── EchoLox-arm64            ← Cross-compiled Binaries
│   ├── EchoLox-armv7
│   ├── EchoLox-amd64
│   └── start.sh
├── config/
│   └── EchoLox.cfg              ← Default-Konfiguration
├── daemon/
│   └── EchoLox                  ← Init-Script (start/stop/status/restart)
├── webfrontend/
│   └── htmlauth/
│       └── index.php              ← LoxBerry Nav-Wrapper → iframe zur EchoLox UI
├── icons/
│   ├── EchoLox.png
│   └── EchoLox_icon.png
└── dpkg/
    └── apt                        ← leer (Go-Binary hat keine Deps)
```

### Installationspfade

| Plugin-Dir | Installiert nach |
|---|---|
| `bin/` | `/opt/loxberry/bin/plugins/EchoLox/` |
| `config/` | `/opt/loxberry/config/plugins/EchoLox/` |
| `data/` | `/opt/loxberry/data/plugins/EchoLox/` |
| `log/` | `/opt/loxberry/log/plugins/EchoLox/` |
| `daemon/` | Init-Script, wird beim Boot ausgeführt |
| `webfrontend/htmlauth/` | `/opt/loxberry/webfrontend/htmlauth/plugins/EchoLox/` |

### plugin.cfg

```ini
[AUTHOR]
NAME = Johannes Battlogg
EMAIL = johannes@battlogg.org

[PLUGIN]
VERSION = 1.0.0
NAME = EchoLox
FOLDER = EchoLox
TITLE = HA Bridge (Hue → Loxone)

[AUTOUPDATE]
RELEASECFG = https://raw.githubusercontent.com/BattloXX/EchoLox/master/release.cfg

[SYSTEM]
REBOOT = false
LB_MINIMUM = 2.0.0
ARCHITECTURES = rpi,x86
INTERFACE = 2.0
```

### postroot.sh — Architektur-Detection

```bash
#!/bin/bash
BINDIR="$LBHOMEDIR/bin/plugins/EchoLox"
ARCH=$(uname -m)

case "$ARCH" in
  aarch64) cp "$BINDIR/EchoLox-arm64" "$BINDIR/EchoLox" ;;
  armv7l)  cp "$BINDIR/EchoLox-armv7" "$BINDIR/EchoLox" ;;
  x86_64)  cp "$BINDIR/EchoLox-amd64" "$BINDIR/EchoLox" ;;
  *) echo "<FAIL> Unsupported architecture: $ARCH"; exit 2 ;;
esac

chmod +x "$BINDIR/EchoLox"
exit 0
```

### daemon/EchoLox — Init-Script

```bash
#!/bin/bash
LBHOMEDIR="/opt/loxberry"
BINARY="$LBHOMEDIR/bin/plugins/EchoLox/EchoLox"
CFGFILE="$LBHOMEDIR/config/plugins/EchoLox/EchoLox.cfg"
PIDFILE="/var/run/EchoLox.pid"

case "$1" in
  start)
    "$BINARY" --config "$CFGFILE" &
    echo $! > "$PIDFILE"
    echo "<OK> EchoLox started (PID $(cat $PIDFILE))"
    ;;
  stop)
    if [ -f "$PIDFILE" ]; then
      kill $(cat "$PIDFILE") && rm "$PIDFILE"
      echo "<OK> EchoLox stopped"
    fi
    ;;
  status)
    [ -f "$PIDFILE" ] && kill -0 $(cat "$PIDFILE") 2>/dev/null \
      && echo "running" || echo "stopped"
    ;;
  restart)
    $0 stop; sleep 1; $0 start
    ;;
esac
```

### webfrontend/htmlauth/index.php — LoxBerry Wrapper

```php
<?php
require_once "loxberry_web.php";

$cfg  = parse_ini_file(
  $ENV['LBPCFG'] . "/EchoLox.cfg", true
);
$port = $cfg['server']['port'] ?? 8083;
$host = $_SERVER['HTTP_HOST'];

lbheader("HA Bridge", "EchoLox", "");
?>
<iframe
  src="http://<?= htmlspecialchars($host) ?>:<?= (int)$port ?>/ui/"
  style="width:100%;height:calc(100vh - 120px);border:none;">
</iframe>
<?php lbfooter(); ?>
```

### LoxBerry Umgebungsvariablen im Go-Code

```go
// internal/bridge/config.go
func lbPath(envKey, fallback string) string {
    if v := os.Getenv(envKey); v != "" {
        return v
    }
    return fallback
}

var (
    DataDir   = lbPath("LBPDATA",   "./data")
    ConfigDir = lbPath("LBPCFG",    "./config")
    LogDir    = lbPath("LBPLOG",    "./log")
    BinDir    = lbPath("LBPBIN",    "./bin")
)
```

---

## Implementierungsphasen

| Phase | Inhalt | Aufwand |
|---|---|---|
| 1 | UPnP/SSDP + Hue API Skeleton (Alexa erkennt Bridge) | 2–3 Tage |
| 2 | Device Model, JSON-Store, Auto-Namens-Generierung | 1 Tag |
| 3 | Loxone HTTP-Transport + UDP-Transport | 1–2 Tage |
| 4 | Loxone Verifikation (LoxAPP3.json, Status-Anzeige) | 1 Tag |
| 5 | Web-UI: Geräte, Status, Einstellungen | 2 Tage |
| 6 | Web-UI: Import (Upload, Vorschau, Mapping) | 1 Tag |
| 7 | MQTT-Gateway Auto-Registrierung | 0.5 Tage |
| 8 | LoxBerry Plugin-Paket, Daemon, Install-Hooks | 0.5 Tage |
| 9 | DietPi/Raspberry Pi Testing | 1 Tag |
| **Gesamt** | | **~10–13 Tage** |

Phase 1 ist das höchste Risiko: SSDP-Pakete und Hue-API-Response müssen exakt stimmen, damit Alexa die Bridge akzeptiert.

---

## Build & Cross-Compilation

```bash
# arm64 (Raspberry Pi 4, Orange Pi)
GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o bin/EchoLox-arm64 ./cmd/EchoLox

# armv7 (Raspberry Pi 2/3, 32-bit)
GOOS=linux GOARCH=arm GOARM=7 go build -ldflags="-s -w" -o bin/EchoLox-armv7 ./cmd/EchoLox

# amd64 (x86 DietPi VM)
GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o bin/EchoLox-amd64 ./cmd/EchoLox

# Plugin-ZIP erstellen
zip -r EchoLox-1.0.0.zip EchoLox/
```
