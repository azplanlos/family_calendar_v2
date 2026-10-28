# Family Calendar v2

A serverless family calendar system that renders events, weather, and a monthly overview onto a 3-color e-ink display (800x480, black/white/red). The system consists of a Java backend running on AWS Lambda and an ESP32-S3 firmware that drives the physical display.

> This is the successor to [family_calendar v1](https://github.com/Froschi1860/family_calendar). The v2 rewrite replaces the original architecture with a serverless backend, a dedicated ESP32 firmware with OTA updates, and a cleaner separation between image generation and display rendering.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        AWS Cloud                            │
│                                                             │
│  EventBridge (every 6h)                                     │
│       │                                                     │
│       ▼                                                     │
│  ┌─────────────────────┐      ┌────────────────────┐        │
│  │ CreateKalenderImage │────▶ │   S3 Bucket        │       │
│  │ (Lambda Handler)    │      │  - calendar.png    │        │
│  │                     │      │  - calendar.bin    │        │
│  └─────────────────────┘      └────────────────────┘        │
│       │  Fetches:                       ▲                   │
│       │  - iCal feeds                   │                   │
│       │  - OpenWeatherMap               │                   │
│       │  - Holidays (jollyday)          │                   │
│       │                                 │                   │
│  ┌─────────────────────┐                │                   │
│  │ ServeCalendarImage  │ ───────────────┘                   │
│  │ (Lambda Func. URL)  │  Reads calendar.bin from S3        │
│  └─────────────────────┘                                    │
│            ▲                                                │
└────────────┼────────────────────────────────────────────────┘
             │ HTTPS (Bearer Token)
             │
┌────────────┼───────────────────────┐
│  ESP32-S3  │ (LOLIN S3 Pro)        │
│            │                       │
│  ┌─────────┴────────────────┐      │
│  │ Firmware                 │      │
│  │  - Fetch calendar.bin    │      │
│  │  - Display on e-ink      │      │
│  │  - Battery monitoring    │      │
│  │  - OTA updates (GitHub)  │      │
│  │  - Deep sleep            │      │
│  └──────────────────────────┘      │
│            │                       │
│  ┌─────────┴────────────────┐      │
│  │ 7.5" E-Ink Display       │      │
│  │ 800x480, 3-color (BWR)   │      │
│  └──────────────────────────┘      │
└────────────────────────────────────┘
```

### Backend (Java / AWS Lambda)

The backend is a Java 21 application packaged as an uber-JAR and deployed to AWS Lambda via the Serverless Framework.

**Two Lambda functions:**

1. **CreateKalenderImage** — Triggered every 6 hours by EventBridge. Fetches calendar events from multiple iCal feeds, loads weather data from OpenWeatherMap, and renders an 800x480 3-color bitmap. Uploads both a PNG (for browser preview) and a raw bitplane binary (`calendar.bin`) to S3.

2. **ServeCalendarImage** — Exposed via a Lambda Function URL. Authenticates the ESP32 via Bearer token, reads `calendar.bin` from S3, and returns it with an `X-Next-Update-Seconds` header that tells the device how long to sleep.

**Key components:**

| Class | Responsibility |
|-------|---------------|
| `EventRepository` | Aggregates events from family iCal, school calendar, vacation feed, and public holidays |
| `IcalParser` | Parses iCal feeds using ical4j |
| `IcalMapping` | MapStruct mapper from ical4j `VEvent` to internal `Event` model |
| `HolidayProvider` | Public holidays via jollyday (configurable country/state) |
| `WeatherService` | 3-day forecast from OpenWeatherMap OneCall API 3.0 |
| `ImageRenderer` | Renders the 800x480 3-color image (header, day columns, weather, month calendar, footer) |
| `BitplaneExporter` | Converts the rendered image into raw bitplane format for the e-ink display |

### Firmware (ESP32-S3 / PlatformIO)

The firmware runs on a **WeMos LOLIN S3 Pro** board connected to a Waveshare 7.5" 3-color e-ink display (800x480).

**Lifecycle:**

1. Boot from deep sleep
2. Load WiFi/API config from NVS flash (first boot triggers serial setup wizard)
3. Connect to WiFi
4. Check for OTA firmware updates via GitHub Releases
5. Fetch `calendar.bin` from the Lambda Function URL
6. Draw battery indicator and firmware version into the image buffer
7. Refresh the e-ink display
8. Enter deep sleep for the duration specified by the backend

**Libraries:** GxEPD2 (e-paper driver), Adafruit GFX

## Prerequisites

### Backend

- **Java 21** with `JAVA_HOME` set
- Maven Wrapper is included (automatically downloads Maven 3.9.9)
- AWS account with Serverless Framework configured
- OpenWeatherMap API key (OneCall 3.0)

### Firmware

- [PlatformIO](https://platformio.org/) (CLI or VS Code extension)
- USB connection to LOLIN S3 Pro for initial flash

## Building

### Backend

The project uses the Maven Wrapper. No separate Maven installation required.

```bash
# Compile (includes annotation processing: Lombok + MapStruct)
.\mvnw.cmd clean compile

# Run tests
.\mvnw.cmd clean test

# Build uber-JAR (for Lambda deployment)
.\mvnw.cmd clean package

# Build without tests
.\mvnw.cmd clean package -DskipTests
```

### Firmware

```bash
# Build firmware
pio run -d firmware

# Upload to connected board
pio run -d firmware -t upload

# Monitor serial output
pio device monitor -d firmware
```

## Deployment

Deployment is automated via GitHub Actions on release:

1. **build-jar** — Builds the Java uber-JAR with Maven
2. **build-firmware** — Builds the firmware binary with PlatformIO
3. **deploy** — Deploys the Lambda functions via Serverless Framework
4. **upload-assets** — Attaches both JAR and `firmware.bin` to the GitHub Release (for OTA)

Manual deployment:

```bash
# Requires SERVERLESS_ACCESS_KEY in environment
serverless deploy --stage prod
```

## Environment Variables

### Backend (Lambda / serverless.yml params)

| Variable | Description | Example |
|----------|-------------|---------|
| `CALENDAR_FEED` | Primary family iCal feed URL | `https://example.com/cal.ics` |
| `SCHOOL_CALENDAR_FEED` | School calendar iCal feed (optional) | |
| `CUSTODY_CALENDAR_FEED` | Shared-custody (Wechselmodell) iCal feed from [wechselplan.de](https://wechselplan.de) (optional) | |
| `CUSTODY_MALE_MATCH` | Name substring identifying the father in the custody feed titles (e.g. `Andreas Zöllner`) | |
| `VACATION_CALENDAR_FEED` | School vacation feed (optional) | `https://www.feiertage-deutschland.de/kalender-download/ics/schulferien-bayern.ics` |
| `HOLIDAY_COUNTRY` | ISO 3166-1 alpha-2 country code for public holidays | `de` |
| `HOLIDAY_STATE` | State/region code for public holidays | `by` |
| `OPENWEATHER_API_KEY` | OpenWeatherMap OneCall API 3.0 key | |
| `OPENWEATHER_LAT` | Latitude for weather forecast | `48.1351` |
| `OPENWEATHER_LON` | Longitude for weather forecast | `11.5820` |
| `API_SECRET` | Shared secret for ESP32 authentication (Bearer token) | |
| `UPDATE_INTERVAL_MINUTES` | Minutes between scheduled image regenerations | `360` |

See `.env.example` for a local reference.

### Firmware (NVS Flash — configured via serial wizard)

| Setting | Description |
|---------|-------------|
| `wifiSsid` | WiFi network name |
| `wifiPassword` | WiFi password |
| `calendarUrl` | Lambda Function URL endpoint |
| `apiSecret` | Bearer token (must match backend `API_SECRET`) |
| `githubOwner` | GitHub repository owner (for OTA) |
| `githubRepo` | GitHub repository name (for OTA) |
| `githubToken` | GitHub token (optional, for private repos) |

On first boot, the firmware starts a serial setup wizard to configure these values. They are persisted in NVS flash and survive reboots.

## Project Structure

```
family_calendar_v2/
├── src/main/java/de/goForFun/familienkalender/
│   ├── CreateKalenderImage.java    # Lambda handler: orchestration
│   ├── ServeCalendarImage.java     # Lambda handler: serve binary to ESP32
│   ├── EventRepository.java        # Multi-source event aggregation
│   ├── IcalParser.java             # iCal feed parsing
│   ├── ImageRenderer.java          # 800x480 e-ink image rendering
│   ├── BitplaneExporter.java       # Image → raw bitplane conversion
│   ├── WeatherService.java         # OpenWeatherMap integration
│   ├── HolidayProvider.java        # Public holiday provider (jollyday)
│   └── model/
│       ├── Event.java              # Event record
│       ├── EventSource.java        # CALENDAR | HOLIDAY | SCHOOL | VACATION
│       ├── RenderData.java         # Data transfer object for rendering
│       └── WeatherDay.java         # Weather forecast day
├── firmware/
│   ├── src/main.cpp                # ESP32 firmware entry point
│   ├── include/
│   │   ├── config.h                # Pin definitions, display config
│   │   ├── nvs_config.h            # NVS flash configuration
│   │   └── ota_update.h            # OTA update via GitHub Releases
│   └── platformio.ini              # PlatformIO build configuration
├── serverless.yml                  # AWS deployment configuration
├── pom.xml                         # Maven build configuration
├── .github/workflows/              # CI/CD pipelines
│   ├── build-jar.yml               # Build Java artifact
│   ├── build-firmware.yml          # Build firmware artifact
│   ├── deploy.yml                  # CI on push to master
│   └── release.yml                 # Full release pipeline
└── .env.example                    # Environment variable reference
```

## Hardware

### Components

| Component | Model | Notes |
|-----------|-------|-------|
| Microcontroller | WeMos LOLIN S3 Pro (ESP32-S3) | 16 MB Flash, OPI PSRAM, USB-C, onboard LiPo charger |
| E-Ink Display | Waveshare 7.5" 3-color (B/W/R) | 800x480 pixels, SPI interface |
| Battery | LiPo single cell (3.7V) | Connected to LOLIN S3 Pro battery port |
| Power | USB-C or LiPo | Board handles charging when USB connected |

### Pin Connections

The display connects to the LOLIN S3 Pro via jumper wires on the SPI bus (HSPI):

| Display Pin | GPIO | Function |
|-------------|------|----------|
| DIN (MOSI)  | 11   | SPI data out |
| CLK (SCLK)  | 12   | SPI clock |
| CS           | 39   | Chip select |
| DC           | 40   | Data/Command |
| RST          | 41   | Reset |
| BUSY         | 42   | Busy signal (display is refreshing) |

Battery voltage is read via the onboard voltage divider:

| Function | GPIO | Notes |
|----------|------|-------|
| BAT_ADC  | 3    | ADC input through board voltage divider |

### Wiring Diagram

```
    LOLIN S3 Pro (ESP32-S3)                     Waveshare 7.5" E-Ink
   ┌───────────────────────────┐               ┌──────────────────┐
   │          [USB-C]          │               │   800x480, 3-C   │
   │                           │               │                  │
   │  GPIO11 (MOSI)  ──────────┼── (Blue) ─────┼── DIN            │
   │  GPIO12 (SCLK)  ──────────┼── (Yellow) ───┼── CLK            │
   │  GPIO39 (CS)    ──────────┼── (Orange) ───┼── CS             │
   │  GPIO40 (DC)    ──────────┼── (Green) ────┼── DC             │
   │  GPIO41 (RST)   ──────────┼── (White) ────┼── RST            │
   │  GPIO42 (BUSY)  ──────────┼── (Purple) ───┼── BUSY           │
   │  3V3            ──────────┼── (Red) ──────┼── VCC            │
   │  GND            ──────────┼── (Black) ────┼── GND            │
   │                           │               │                  │
   │  GPIO3 ◄── [Voltage Div]  │               └──────────────────┘
   │                           │
   │  [JST] ◄── LiPo 3.7V      │
   └───────────────────────────┘
```

### Simplified Schematic

```
                        +3.3V
                          │
   ┌──────────────────────┼──────────────────────────────────────┐
   │                      │                                      │
   │   LOLIN S3 Pro ──────┘                                      │
   │   ┌────────────────────────────┐       ┌────────────────┐   │
   │   │                            │       │                │   │
   │   │  USB-C (Power + Data) ◄────┼───────┼── PC/Charger   │   │
   │   │                            │       └────────────────┘   │
   │   │                            │                            │
   │   │  HSPI Bus:                 │       Jumper Wires         │
   │   │    GPIO11 ─── MOSI ────────┼──────────────►┐            │
   │   │    GPIO12 ─── SCLK ────────┼──────────────►│            │
   │   │    GPIO39 ─── CS ──────────┼──────────────►│            │
   │   │    GPIO40 ─── DC ──────────┼──────────────►│  E-Ink     │
   │   │    GPIO41 ─── RST ─────────┼──────────────►│  Display   │
   │   │    GPIO42 ◄── BUSY ────────┼──────────────►│  (SPI)     │
   │   │    3V3 ────────────────────┼──────────────►│  VCC       │
   │   │    GND ────────────────────┼──────────────►│  GND       │
   │   │                            │               └───────┘    │
   │   │                            │                            │
   │   │  GPIO3 ◄── ADC             │                            │
   │   │              │             │                            │
   │   │         [Voltage Divider]  │                            │
   │   │              │             │                            │
   │   │  BAT+  ──────┘             │       ┌────────────────┐   │
   │   │  BAT-  ─── GND             │       │  LiPo 3.7V     │   │
   │   │  [JST Connector]  ◄────────┼───────┼── Battery      │   │
   │   │                            │       └────────────────┘   │
   │   └────────────────────────────┘                            │
   │                                                             │
   └─────────────────────────────────────────────────────────────┘
```

### Notes on Assembly

- The display is connected via **jumper wires** from the LOLIN S3 Pro GPIO header to the Waveshare e-ink HAT connector (8 wires total: MOSI, SCLK, CS, DC, RST, BUSY, VCC, GND).
- The battery connects to the JST-PH 2-pin connector on the board. Charging is handled automatically when USB-C is plugged in.
- The board's built-in voltage divider on GPIO3 reads battery voltage without additional external components.
- No external pull-up/pull-down resistors are needed.

### Wiring Diagram (SVG)

A visual wiring diagram is provided as an SVG file for use in documentation or printing:

![Wiring Diagram](docs/wiring-diagram.svg)

The SVG shows the physical jumper wire connections between the LOLIN S3 Pro pin header and the Waveshare e-ink display connector.

### Fritzing Wiring Table

| From (LOLIN S3 Pro Header) | Wire Color (suggested) | To (Waveshare E-Ink HAT) |
|----------------------------|------------------------|--------------------------|
| 3V3 | Red | VCC |
| GND | Black | GND |
| GPIO11 | Blue | DIN (MOSI) |
| GPIO12 | Yellow | CLK (SCLK) |
| GPIO39 | Orange | CS |
| GPIO40 | Green | DC |
| GPIO41 | White | RST |
| GPIO42 | Purple | BUSY |

## Display Layout

The rendered image is divided into these areas:

- **Header** — Current date (right-aligned), separator line
- **Day columns** (left half) — "Heute" (today) and "Morgen" (tomorrow) with events
  - All-day events: red background with white text
  - Timed events: time + participant badge + title
  - QR codes for events with URLs
- **Weather forecast** (top right) — 3-day forecast with icons and temperature
- **Month calendar** (bottom right) — 5-week grid with event indicators per participant
- **Footer** — Last update timestamp, version info

## Shared-Custody Calendar (Wechselmodell)

The optional shared-custody overlay is driven by an iCal feed generated by [wechselplan.de](https://wechselplan.de). When `CUSTODY_CALENDAR_FEED` points to such a feed and `CUSTODY_MALE_MATCH` names the father, the renderer adds a care pictogram (man/woman, WC-sign style) next to the "Heute"/"Morgen" headers and lightly shades the father's days in the month calendar. On handover days a small label above the pictogram shows the time from the father's perspective, e.g. `(ab 16:00)` (takes over) or `(bis 16:00)` (hands over).

The feed mixes several event types, distinguished by their `UID` prefix:

| UID prefix | Meaning | Effect |
|------------|---------|--------|
| `wp-care-` | All-day care block ("Lukas bei …") | Assigns the day to the named party |
| `wp-vacation-` | All-day vacation block ("Lukas: Urlaub – …") | Counts as normal care for the named party |
| `wp-handover-` | Timed handover ("Wechsel zu …") | Marks a switch day with the handover time |
| `wp-schoolvac-` | School holidays ("Schulferien: …") | Ignored for care determination |

The party per day is derived from the `SUMMARY` via the `CUSTODY_MALE_MATCH` substring; a handover's first half is the previous day's (handing-over) party, the second half the party named in the handover event. If you need to double-check feed specifics, the source is [wechselplan.de](https://wechselplan.de).

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Backend language | Java 21 |
| Build tool | Maven 3.9.9 (via wrapper) |
| Cloud provider | AWS (Lambda, S3, EventBridge) |
| Deployment | Serverless Framework |
| iCal parsing | ical4j 4.2.3 |
| Object mapping | MapStruct 1.6.3 |
| Holidays | jollyday 2.12.0 |
| Weather | OpenWeatherMap OneCall API 3.0 |
| QR codes | ZXing 3.5.3 |
| Firmware platform | ESP32-S3 (LOLIN S3 Pro) |
| Firmware framework | Arduino (PlatformIO) |
| Display driver | GxEPD2 |
| Display | Waveshare 7.5" 3-color e-ink (800x480) |

## License

Private project.
