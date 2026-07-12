/**
 * Family Calendar - E-Ink Display Firmware
 * 
 * Board: WeMos LOLIN S3 Pro (ESP32-S3)
 * Display: 3-color e-ink 800x480 (black/white/red)
 * 
 * This firmware fetches a pre-rendered bitmap from the backend API
 * and displays it on the e-ink panel. It uses deep sleep between
 * update cycles to conserve power.
 */

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <SPI.h>

// GxEPD2 for 3-color 800x480 e-paper (e.g. Waveshare 7.5" B/W/R)
#include <GxEPD2_3C.h>
#include <Adafruit_GFX.h>

#include "config.h"
#include "nvs_config.h"
#include "ota_update.h"

// ============================================================
// Display Instance
// ============================================================
// Using GxEPD2_750c_Z08 for 800x480 3-color (Waveshare 7.5" V2 B/W/R)
// Adjust the driver class if you use a different panel.
SPIClass displaySPI(HSPI);

GxEPD2_3C<GxEPD2_750c_Z08, GxEPD2_750c_Z08::HEIGHT / 2> display(
    GxEPD2_750c_Z08(EPD_CS, EPD_DC, EPD_RST, EPD_BUSY)
);

// ============================================================
// Image Buffer
// ============================================================
// The backend delivers a raw bitmap: 2 bits per pixel, 800x480 = 96000 bytes
static const uint32_t IMAGE_SIZE_BW = (DISPLAY_WIDTH * DISPLAY_HEIGHT) / 8;   // black channel
static const uint32_t IMAGE_SIZE_RED = (DISPLAY_WIDTH * DISPLAY_HEIGHT) / 8;  // red channel

uint8_t* blackBuffer = nullptr;
uint8_t* redBuffer = nullptr;

// Deep sleep duration in seconds, updated from backend header
uint64_t deepSleepSeconds = UPDATE_INTERVAL_MIN * 60ULL; // fallback

// ============================================================
// Forward Declarations
// ============================================================
bool connectWiFi();
bool fetchImage();
void showImage(int batteryPercent);
void drawBatteryIntoBuffer(int batteryPercent);
void drawFirmwareVersionIntoBuffer();
void showErrorImage();
void handleConnectionError();
void enterDeepSleep();
float readBatteryVoltage();
int readBatteryPercent();

// ============================================================
// Setup
// ============================================================
void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n[FamilyCalendar] Starting...");
    Serial.printf("[FamilyCalendar] Firmware %s\n", FW_VERSION_STRING);

    // Load configuration from NVS
    if (!configInit()) {
        Serial.println("[Config] Keine Konfiguration gefunden.");
        Serial.println("[Config] Starte Setup-Wizard...");
        Serial.println("[Config] (Sende beliebiges Zeichen um zu starten)");

        // Wait for serial connection (user opens terminal)
        unsigned long waitStart = millis();
        while (!Serial.available() && (millis() - waitStart) < 30000) {
            delay(100);
        }

        configRunSetupWizard();

        Serial.println("[Config] Neustart...");
        delay(500);
        ESP.restart();
        return;
    }

    const DeviceConfig& cfg = configGet();
    Serial.printf("[Config] WiFi SSID: %s\n", cfg.wifiSsid);

    // Read battery level
    float batteryVoltage = readBatteryVoltage();
    int batteryPercent = readBatteryPercent();
    Serial.printf("[Battery] Voltage: %.2fV => %d%%\n", batteryVoltage, batteryPercent);

    // Allocate buffers in PSRAM if available
    blackBuffer = (uint8_t*)ps_malloc(IMAGE_SIZE_BW);
    redBuffer = (uint8_t*)ps_malloc(IMAGE_SIZE_RED);

    if (!blackBuffer || !redBuffer) {
        Serial.println("[ERROR] Failed to allocate image buffers!");
        enterDeepSleep();
        return;
    }

    // Initialize SPI for display
    displaySPI.begin(EPD_SCLK, -1, EPD_MOSI, EPD_CS);
    display.epd2.selectSPI(displaySPI, SPISettings(4000000, MSBFIRST, SPI_MODE0));

    // Initialize display
    display.init(115200, true, 2, false);
    display.setRotation(2); // 180° for GFX drawing operations (error image)

    // Connect to WiFi and fetch image, then overlay battery indicator
    if (connectWiFi()) {
        // Check for OTA update before fetching calendar image
        checkAndPerformOTA(); // If update found, device reboots and never returns

        if (fetchImage()) {
            showImage(batteryPercent);
            Serial.println("[OK] Display updated successfully.");
            WiFi.disconnect(true);
        } else {
            Serial.println("[ERROR] Failed to fetch image from backend.");
            WiFi.disconnect(true);
            handleConnectionError(); // never returns (deep sleep or restart)
        }
    } else {
        Serial.println("[ERROR] WiFi connection failed.");
        handleConnectionError(); // never returns (deep sleep or restart)
    }

    // Enter deep sleep
    if (DEEP_SLEEP_ENABLED) {
        enterDeepSleep();
    }
}

// ============================================================
// Loop (not used with deep sleep)
// ============================================================
void loop() {
    // With deep sleep enabled, this is never reached.
    // If deep sleep is disabled, update periodically:
    delay(UPDATE_INTERVAL_MIN * 60UL * 1000UL);

    if (connectWiFi()) {
        if (fetchImage()) {
            showImage(readBatteryPercent());
        }
        WiFi.disconnect(true);
    }
}

// ============================================================
// WiFi Connection
// ============================================================
bool connectWiFi() {
    const DeviceConfig& cfg = configGet();
    Serial.printf("[WiFi] Connecting to %s", cfg.wifiSsid);
    WiFi.mode(WIFI_STA);
    WiFi.begin(cfg.wifiSsid, cfg.wifiPassword);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 40) {
        delay(500);
        Serial.print(".");
        attempts++;
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("\n[WiFi] Connected! IP: %s\n", WiFi.localIP().toString().c_str());
        return true;
    }

    Serial.println("\n[WiFi] Connection failed!");
    return false;
}

// ============================================================
// Fetch Image from Lambda Function URL
// ============================================================
bool fetchImage() {
    const DeviceConfig& cfg = configGet();
    HTTPClient http;
    WiFiClientSecure secureClient;

    // Skip certificate verification for Lambda Function URL
    // (alternatively, embed the Amazon Root CA for production use)
    secureClient.setInsecure();

    Serial.println("[HTTP] Fetching calendar.bin from Lambda Function URL...");
    http.begin(secureClient, cfg.calendarUrl);
    http.setTimeout(30000);
    http.addHeader("Authorization", String("Bearer ") + cfg.apiSecret);

    // Collect custom response header for dynamic sleep duration
    const char* headersToCollect[] = {"X-Next-Update-Seconds"};
    http.collectHeaders(headersToCollect, 1);

    int httpCode = http.GET();

    if (httpCode == HTTP_CODE_OK) {
        // Read X-Next-Update-Seconds header for dynamic deep sleep
        if (http.hasHeader("X-Next-Update-Seconds")) {
            long headerVal = http.header("X-Next-Update-Seconds").toInt();
            if (headerVal > 0) {
                deepSleepSeconds = (uint64_t)headerVal;
                Serial.printf("[HTTP] Next update in %llu seconds (from header)\n", deepSleepSeconds);
            }
        } else {
            Serial.printf("[HTTP] No X-Next-Update-Seconds header, using fallback %llu s\n", deepSleepSeconds);
        }

        int contentLength = http.getSize();
        Serial.printf("[HTTP] Response size: %d bytes\n", contentLength);

        WiFiClient* stream = http.getStreamPtr();

        // Expected format: [48000 bytes black plane][48000 bytes red plane]
        uint32_t expectedSize = IMAGE_SIZE_BW + IMAGE_SIZE_RED;
        if (contentLength > 0 && (uint32_t)contentLength != expectedSize) {
            Serial.printf("[WARN] Unexpected content length: %d (expected %lu)\n",
                          contentLength, expectedSize);
        }

        // Read black channel
        uint32_t bytesRead = 0;
        unsigned long timeout = millis() + 30000;
        while (bytesRead < IMAGE_SIZE_BW && millis() < timeout) {
            int available = stream->available();
            if (available > 0) {
                int toRead = min((uint32_t)available, IMAGE_SIZE_BW - bytesRead);
                int read = stream->readBytes(blackBuffer + bytesRead, toRead);
                bytesRead += read;
            }
            yield();
        }
        Serial.printf("[HTTP] Black plane: %lu bytes read\n", bytesRead);

        // Read red channel
        bytesRead = 0;
        timeout = millis() + 30000;
        while (bytesRead < IMAGE_SIZE_RED && millis() < timeout) {
            int available = stream->available();
            if (available > 0) {
                int toRead = min((uint32_t)available, IMAGE_SIZE_RED - bytesRead);
                int read = stream->readBytes(redBuffer + bytesRead, toRead);
                bytesRead += read;
            }
            yield();
        }
        Serial.printf("[HTTP] Red plane: %lu bytes read\n", bytesRead);

        http.end();
        Serial.println("[HTTP] Image received successfully.");
        return true;
    } else if (httpCode == 401) {
        Serial.println("[HTTP] Authentication failed! Check API_SECRET.");
        http.end();
        return false;
    } else {
        Serial.printf("[HTTP] GET failed, code: %d %s\n", httpCode, http.errorToString(httpCode).c_str());
        http.end();
        return false;
    }
}

// ============================================================
// Display Image on E-Paper
// ============================================================
void showImage(int batteryPercent) {
    Serial.println("[Display] Writing image to e-paper...");

    // Draw battery indicator and firmware version directly into the buffers
    // before sending to the display. This avoids partial window artifacts.
    drawBatteryIntoBuffer(batteryPercent);
    drawFirmwareVersionIntoBuffer();

    // Write the combined image (backend bitmap + overlays) as one full refresh
    display.setFullWindow();
    display.writeImage(blackBuffer, redBuffer, 0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, false, false, false);

    display.refresh();
    display.hibernate();
    Serial.println("[Display] Refresh complete, display in hibernate mode.");
}

// ============================================================
// Buffer Pixel Helpers
// These draw directly into blackBuffer/redBuffer without using
// the GxEPD2 partial window mechanism, avoiding transparency
// artifacts and ensuring the full bitmap is sent in one refresh.
//
// Buffer layout: 1 bit per pixel, MSB first, row-major.
// Pixel (x, y) is at byte offset (y * WIDTH + x) / 8, bit 7 - (x % 8).
// A '0' bit = black/red (ink), '1' bit = white (no ink).
// ============================================================

inline void bufferSetPixel(uint8_t* buffer, int x, int y, bool ink) {
    if (x < 0 || x >= DISPLAY_WIDTH || y < 0 || y >= DISPLAY_HEIGHT) return;
    // The backend image is pre-rotated 180° for the physical panel orientation.
    // Overlay coordinates use logical (visible) orientation, so we must flip them
    // to match the buffer layout.
    int px = DISPLAY_WIDTH - 1 - x;
    int py = DISPLAY_HEIGHT - 1 - y;
    uint32_t byteIdx = (py * DISPLAY_WIDTH + px) / 8;
    uint8_t bitMask = 0x80 >> (px % 8);
    if (ink) {
        buffer[byteIdx] &= ~bitMask; // 0 = ink on
    } else {
        buffer[byteIdx] |= bitMask;  // 1 = no ink
    }
}

/// Clear a rectangular area to white in both planes
void bufferClearRect(int rx, int ry, int rw, int rh) {
    for (int py = ry; py < ry + rh; py++) {
        for (int px = rx; px < rx + rw; px++) {
            bufferSetPixel(blackBuffer, px, py, false);
            bufferSetPixel(redBuffer, px, py, false);
        }
    }
}

/// Draw a filled rectangle in the specified plane
void bufferFillRect(uint8_t* buffer, int rx, int ry, int rw, int rh) {
    for (int py = ry; py < ry + rh; py++) {
        for (int px = rx; px < rx + rw; px++) {
            bufferSetPixel(buffer, px, py, true);
        }
    }
}

/// Draw a rectangle outline in the specified plane
void bufferDrawRect(uint8_t* buffer, int rx, int ry, int rw, int rh) {
    for (int px = rx; px < rx + rw; px++) {
        bufferSetPixel(buffer, px, ry, true);
        bufferSetPixel(buffer, px, ry + rh - 1, true);
    }
    for (int py = ry; py < ry + rh; py++) {
        bufferSetPixel(buffer, rx, py, true);
        bufferSetPixel(buffer, rx + rw - 1, py, true);
    }
}

/// Draw a single character (built-in 5x7 font) into the buffer at (cx, cy).
/// Returns the advance width (6 px per char).
/// Minimal embedded 5x7 font for the characters we need in overlays.
static const uint8_t PROGMEM miniFont[][5] = {
    // Space (0x20)
    {0x00, 0x00, 0x00, 0x00, 0x00},
    // '.' (0x2E) - index will be computed
    {0x00, 0x60, 0x60, 0x00, 0x00},
    // '0'-'9' (0x30-0x39)
    {0x3E, 0x51, 0x49, 0x45, 0x3E}, // 0
    {0x00, 0x42, 0x7F, 0x40, 0x00}, // 1
    {0x42, 0x61, 0x51, 0x49, 0x46}, // 2
    {0x21, 0x41, 0x45, 0x4B, 0x31}, // 3
    {0x18, 0x14, 0x12, 0x7F, 0x10}, // 4
    {0x27, 0x45, 0x45, 0x45, 0x39}, // 5
    {0x3C, 0x4A, 0x49, 0x49, 0x30}, // 6
    {0x01, 0x71, 0x09, 0x05, 0x03}, // 7
    {0x36, 0x49, 0x49, 0x49, 0x36}, // 8
    {0x06, 0x49, 0x49, 0x29, 0x1E}, // 9
    // '%' (0x25)
    {0x23, 0x13, 0x08, 0x64, 0x62},
    // 'F' (0x46)
    {0x7F, 0x09, 0x09, 0x09, 0x01},
    // 'W' (0x57)
    {0x3F, 0x40, 0x38, 0x40, 0x3F},
    // 'v' (0x76)
    {0x1C, 0x20, 0x40, 0x20, 0x1C},
    // '|' (0x7C)
    {0x00, 0x00, 0x7F, 0x00, 0x00},
};

/// Map a character to its index in the miniFont table. Returns -1 if not found.
int miniFontIndex(char c) {
    if (c == ' ') return 0;
    if (c == '.') return 1;
    if (c >= '0' && c <= '9') return 2 + (c - '0');
    if (c == '%') return 12;
    if (c == 'F') return 13;
    if (c == 'W') return 14;
    if (c == 'v') return 15;
    if (c == '|') return 16;
    return -1;
}

int bufferDrawChar(uint8_t* buffer, int cx, int cy, char c) {
    int idx = miniFontIndex(c);
    if (idx < 0) return 6; // unsupported char, skip
    for (int col = 0; col < 5; col++) {
        uint8_t line = pgm_read_byte(&miniFont[idx][col]);
        for (int row = 0; row < 7; row++) {
            if (line & (1 << row)) {
                bufferSetPixel(buffer, cx + col, cy + row, true);
            }
        }
    }
    return 6; // 5px glyph + 1px spacing
}

/// Draw a string into the buffer starting at (sx, sy)
void bufferDrawString(uint8_t* buffer, int sx, int sy, const char* str) {
    int x = sx;
    while (*str) {
        x += bufferDrawChar(buffer, x, sy, *str);
        str++;
    }
}

// ============================================================
// Draw Battery Indicator into Buffer
// Positioned at top-right of the visible image.
// The backend image is pre-oriented for the physical panel, so
// "top-right visible" = physical pixel (WIDTH - margin, 0 + margin).
// ============================================================
void drawBatteryIntoBuffer(int batteryPercent) {
    // Position: top-right area of physical panel
    const int batW = 20;
    const int batH = 10;
    const int nippleW = 2;
    const int nippleH = 4;
    const int margin = 5;

    // Battery icon top-right corner (leave space for percentage text to the right)
    // Layout: [battery icon][nipple] [percentage%]
    // Total width estimate: batW + nippleW + 4 + 4*6(chars) ≈ 50px
    int batX = DISPLAY_WIDTH - margin - 50;
    int batY = margin;

    // Clear background area behind overlay (white)
    bufferClearRect(batX - 2, batY - 2, 54, batH + 4);

    // Draw battery outline (black plane)
    bufferDrawRect(blackBuffer, batX, batY, batW, batH);

    // Draw nipple (positive terminal)
    bufferFillRect(blackBuffer, batX + batW, batY + (batH - nippleH) / 2, nippleW, nippleH);

    // Fill level inside battery body
    int fillW = (int)((batW - 4) * batteryPercent / 100.0f);
    if (fillW > 0) {
        if (batteryPercent <= 20) {
            // Red fill for low battery
            bufferFillRect(redBuffer, batX + 2, batY + 2, fillW, batH - 4);
        } else {
            // Black fill for normal battery
            bufferFillRect(blackBuffer, batX + 2, batY + 2, fillW, batH - 4);
        }
    }

    // Draw percentage text next to battery icon (black)
    char percentStr[8];
    snprintf(percentStr, sizeof(percentStr), "%d%%", batteryPercent);
    int textX = batX + batW + nippleW + 4;
    int textY = batY + 1;
    bufferDrawString(blackBuffer, textX, textY, percentStr);
}

// ============================================================
// Draw Firmware Version into Buffer
// Positioned at bottom-left, right after the backend version.
// The backend renders its version at approximately x=10, y=HEIGHT-12
// with ~10pt font. The firmware version is drawn after it with a
// separator, using the small 5x7 built-in font at the very bottom.
// ============================================================
void drawFirmwareVersionIntoBuffer() {
    // Position: bottom-left of physical panel, offset to the right
    // to not overlap with the backend version text.
    // Backend version "v1.2.3" takes about 60-80px with its 10pt font.
    // We place firmware version starting at x=80 to leave space.
    const int versionX = 80;
    const int versionY = DISPLAY_HEIGHT - 10; // near bottom edge

    // Build version string: "| FW v0.0.0"
    char fwStr[32];
    snprintf(fwStr, sizeof(fwStr), "| FW v%s", FW_VERSION_STRING);

    // Clear a small area behind the text (avoid blending with footer line)
    int strLen = strlen(fwStr);
    bufferClearRect(versionX, versionY - 1, strLen * 6 + 2, 9);

    // Draw into black plane
    bufferDrawString(blackBuffer, versionX, versionY, fwStr);
}

// ============================================================
// Error Image ("Keine Verbindung" + Network Icon)
// Drawn directly on e-ink using only black/white/red (no grays)
// ============================================================
void showErrorImage() {
    Serial.println("[Display] Showing error image...");

    display.setFullWindow();
    display.firstPage();
    do {
        display.fillScreen(GxEPD_WHITE);

        // --- Network icon (centered, y=160) ---
        // Simple WiFi-style icon: concentric arcs + base dot
        // Drawn as pixel-perfect arcs (no anti-aliasing)
        int cx = DISPLAY_WIDTH / 2;
        int iconY = 160;

        // Base dot (red, 6x6)
        display.fillRect(cx - 3, iconY, 6, 6, GxEPD_RED);

        // Arc 1 (innermost) - black
        display.drawCircle(cx, iconY + 2, 14, GxEPD_BLACK);
        display.drawCircle(cx, iconY + 2, 15, GxEPD_BLACK);
        // Erase bottom half of arc
        display.fillRect(cx - 20, iconY + 2, 40, 20, GxEPD_WHITE);

        // Arc 2 (middle) - black
        display.drawCircle(cx, iconY + 2, 26, GxEPD_BLACK);
        display.drawCircle(cx, iconY + 2, 27, GxEPD_BLACK);
        // Erase bottom half
        display.fillRect(cx - 32, iconY + 2, 64, 32, GxEPD_WHITE);

        // Arc 3 (outermost) - black
        display.drawCircle(cx, iconY + 2, 38, GxEPD_BLACK);
        display.drawCircle(cx, iconY + 2, 39, GxEPD_BLACK);
        // Erase bottom half
        display.fillRect(cx - 44, iconY + 2, 88, 44, GxEPD_WHITE);

        // Redraw base dot on top (may have been clipped)
        display.fillRect(cx - 3, iconY, 6, 6, GxEPD_RED);

        // Red "X" over the icon (diagonal cross, strike-through)
        for (int i = -1; i <= 1; i++) {
            display.drawLine(cx - 30 + i, iconY - 40, cx + 30 + i, iconY + 8, GxEPD_RED);
            display.drawLine(cx + 30 + i, iconY - 40, cx - 30 + i, iconY + 8, GxEPD_RED);
        }

        // --- "Keine Verbindung" text (centered, below icon) ---
        // Use built-in 6x8 font, scaled 3x for readability
        display.setFont(nullptr);
        display.setTextColor(GxEPD_BLACK);
        display.setTextSize(3);

        const char* line1 = "Keine";
        const char* line2 = "Verbindung";

        // Center text (6 pixels per char at scale 3 = 18px per char)
        int16_t x1 = cx - (strlen(line1) * 18) / 2;
        int16_t x2 = cx - (strlen(line2) * 18) / 2;
        int16_t textY = iconY + 30;

        display.setCursor(x1, textY);
        display.print(line1);
        display.setCursor(x2, textY + 30);
        display.print(line2);

        // Reset text size
        display.setTextSize(1);

    } while (display.nextPage());

    display.hibernate();
    Serial.println("[Display] Error image displayed.");
}

// ============================================================
// Connection Error Handler
// Prompts user via serial to reset config (10s timeout).
// If no response, shows error image and enters deep sleep.
// ============================================================
void handleConnectionError() {
    Serial.println();
    Serial.println("========================================");
    Serial.println("[ERROR] Verbindung fehlgeschlagen!");
    Serial.println("[ERROR] Konfiguration zuruecksetzen? (j/n)");
    Serial.println("[ERROR] Timeout in 10 Sekunden...");
    Serial.println("========================================");

    // Flush any pending serial input
    while (Serial.available()) {
        Serial.read();
    }

    // Wait for user input with 10s timeout
    unsigned long waitStart = millis();
    bool resetRequested = false;

    while ((millis() - waitStart) < 10000) {
        if (Serial.available()) {
            char c = (char)Serial.read();
            if (c == 'j' || c == 'J' || c == 'y' || c == 'Y') {
                resetRequested = true;
                break;
            } else if (c == 'n' || c == 'N') {
                break;
            }
        }
        delay(50);
    }

    if (resetRequested) {
        Serial.println("[Config] Konfiguration wird geloescht...");
        configClear();
        Serial.println("[Config] Neustart fuer Setup-Wizard...");
        delay(500);
        ESP.restart();
        return;
    }

    Serial.println("[ERROR] Kein Reset. Zeige Fehlerbild...");

    // Show error image on e-ink display
    showErrorImage();

    // Enter deep sleep
    enterDeepSleep();
}

// ============================================================
// Deep Sleep
// ============================================================
void enterDeepSleep() {
    uint64_t sleepMinutes = deepSleepSeconds / 60;
    Serial.printf("[Sleep] Entering deep sleep for %llu minutes (%llu seconds)...\n", sleepMinutes, deepSleepSeconds);
    Serial.flush();

    // Free buffers before sleep
    if (blackBuffer) { free(blackBuffer); blackBuffer = nullptr; }
    if (redBuffer) { free(redBuffer); redBuffer = nullptr; }

    uint64_t sleepUs = deepSleepSeconds * 1000000ULL;
    esp_sleep_enable_timer_wakeup(sleepUs);
    esp_deep_sleep_start();
}

// ============================================================
// Battery Monitoring
// ============================================================
float readBatteryVoltage() {
    // Configure ADC for battery pin
    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);

    // Take multiple samples for stability
    uint32_t sum = 0;
    const int samples = 16;
    for (int i = 0; i < samples; i++) {
        sum += analogRead(BAT_ADC_PIN);
        delay(5);
    }
    uint32_t raw = sum / samples;

    // Convert to voltage
    // ESP32-S3 ADC with 11dB attenuation: 0-3.3V range, 12-bit (0-4095)
    // The LOLIN S3 Pro battery circuit uses a voltage divider,
    // matching the CircuitPython formula: (value / 65535) * reference_voltage
    // CircuitPython uses 16-bit (0-65535), Arduino uses 12-bit (0-4095)
    float voltage = (float)raw / 4095.0f * 3.3f;

    return voltage;
}

int readBatteryPercent() {
    float voltage = readBatteryVoltage();

    // Clamp and map to 0-100% using the same thresholds as CircuitPython code
    float percent = (voltage - BAT_VOLTAGE_EMPTY) / (BAT_VOLTAGE_FULL - BAT_VOLTAGE_EMPTY);
    percent = constrain(percent, 0.0f, 1.0f);

    return (int)(percent * 100.0f);
}
