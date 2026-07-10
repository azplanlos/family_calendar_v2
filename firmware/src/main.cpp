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

// ============================================================
// Forward Declarations
// ============================================================
bool connectWiFi();
bool fetchImage();
void showImage(int batteryPercent);
void drawBatteryIndicator(int batteryPercent);
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
    display.setRotation(0); // No rotation needed; image is pre-rendered in correct orientation

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

    int httpCode = http.GET();

    if (httpCode == HTTP_CODE_OK) {
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

    // First write the backend image using writeImage with both planes
    // writeImage sends black plane to command 0x10 and color plane to command 0x13
    display.writeImage(blackBuffer, redBuffer, 0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, false, false, false);

    // Overlay battery indicator in top-left corner
    drawBatteryIndicator(batteryPercent);

    display.refresh();
    display.hibernate();
    Serial.println("[Display] Refresh complete, display in hibernate mode.");
}

// ============================================================
// Battery Indicator (rendered locally, top-left corner)
// Mimics the old CircuitPython code: battery icon + "XX%" label
// ============================================================
void drawBatteryIndicator(int batteryPercent) {
    // Battery icon dimensions (matching old code position at x=5, y=15)
    const int batX = 5;
    const int batY = 5;
    const int batW = 20;
    const int batH = 10;
    const int nippleW = 2;
    const int nippleH = 4;

    // Use partial window to draw over the backend image
    display.setPartialWindow(0, 0, 80, 20);

    // Draw battery outline (black)
    display.drawRect(batX, batY, batW, batH, GxEPD_BLACK);
    // Draw battery nipple (positive terminal)
    display.fillRect(batX + batW, batY + (batH - nippleH) / 2, nippleW, nippleH, GxEPD_BLACK);

    // Fill level inside battery body
    int fillW = (int)((batW - 4) * batteryPercent / 100.0f);
    if (fillW > 0) {
        uint16_t fillColor = (batteryPercent <= 20) ? GxEPD_RED : GxEPD_BLACK;
        display.fillRect(batX + 2, batY + 2, fillW, batH - 4, fillColor);
    }

    // Draw percentage text next to battery icon
    display.setTextColor(GxEPD_BLACK);
    display.setFont(nullptr); // built-in 6x8 font (no anti-aliasing)
    display.setCursor(batX + batW + nippleW + 4, batY + 1);
    display.print(String(batteryPercent) + "%");
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
    Serial.printf("[Sleep] Entering deep sleep for %d minutes...\n", UPDATE_INTERVAL_MIN);
    Serial.flush();

    // Free buffers before sleep
    if (blackBuffer) { free(blackBuffer); blackBuffer = nullptr; }
    if (redBuffer) { free(redBuffer); redBuffer = nullptr; }

    esp_sleep_enable_timer_wakeup(DEEP_SLEEP_DURATION_US);
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
