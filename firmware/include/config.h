#ifndef CONFIG_H
#define CONFIG_H

// ============================================================
// Firmware Version (Semantic Versioning)
// Overridden by CI build flags when built via GitHub Actions.
// These defaults are used for local development builds only.
// ============================================================
#ifndef FW_VERSION_MAJOR
#define FW_VERSION_MAJOR 0
#endif
#ifndef FW_VERSION_MINOR
#define FW_VERSION_MINOR 0
#endif
#ifndef FW_VERSION_PATCH
#define FW_VERSION_PATCH 0
#endif
#ifndef FW_VERSION_STRING
#define FW_VERSION_STRING "0.0.0"
#endif

// ============================================================
// WiFi Configuration
// ============================================================
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"

// ============================================================
// Backend API Configuration
// ============================================================
// Lambda Function URL for serving calendar.bin
#define CALENDAR_FUNCTION_URL "https://YOUR_FUNCTION_ID.lambda-url.eu-central-1.on.aws/"

// Shared secret for Bearer token authentication
#define API_SECRET "YOUR_SHARED_SECRET"

// ============================================================
// E-Paper Display Pin Definitions (LOLIN S3 Pro Display Port)
// ============================================================
#ifndef EPD_MOSI
#define EPD_MOSI 11
#endif
#ifndef EPD_SCLK
#define EPD_SCLK 12
#endif
#ifndef EPD_CS
#define EPD_CS 39
#endif
#ifndef EPD_DC
#define EPD_DC 40
#endif
#ifndef EPD_RST
#define EPD_RST 41
#endif
#ifndef EPD_BUSY
#define EPD_BUSY 42
#endif

// ============================================================
// Battery Monitoring (ADC on LOLIN S3 Pro)
// ============================================================
// board.A2 = GPIO3 (from CircuitPython pins.c)
#define BAT_ADC_PIN 3

// Voltage thresholds for LiPo (single cell)
// Measured through voltage divider on the board
#define BAT_VOLTAGE_FULL 2.10f
#define BAT_VOLTAGE_EMPTY 1.50f

// ============================================================
// Display Dimensions
// ============================================================
#define DISPLAY_WIDTH 800
#define DISPLAY_HEIGHT 480

// ============================================================
// Update Interval (in minutes)
// ============================================================
#define UPDATE_INTERVAL_MIN 360

// ============================================================
// OTA Update Configuration (GitHub Releases)
// ============================================================
#define GITHUB_OWNER "azplanlos"
#define GITHUB_REPO "family_calendar_v2"
#define GITHUB_FIRMWARE_ASSET "firmware.bin"
// Optional: GitHub Personal Access Token for private repos (leave empty for public)
#define GITHUB_TOKEN ""

// ============================================================
// Deep Sleep
// ============================================================
#define DEEP_SLEEP_ENABLED true
#define DEEP_SLEEP_DURATION_US (UPDATE_INTERVAL_MIN * 60ULL * 1000000ULL)

#endif // CONFIG_H
