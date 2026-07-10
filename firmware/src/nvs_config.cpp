/**
 * NVS Configuration Module
 *
 * Stores device credentials and settings in ESP32 Non-Volatile Storage.
 * On first boot (or after factory reset), runs an interactive serial
 * wizard to collect WiFi, API, and GitHub OTA settings.
 *
 * NVS namespace: "famcal"
 */

#include <Arduino.h>
#include <Preferences.h>
#include "nvs_config.h"

// ============================================================
// Internal State
// ============================================================

static Preferences prefs;
static DeviceConfig currentConfig;
static bool configLoaded = false;

static const char* NVS_NAMESPACE = "famcal";

// NVS Keys
static const char* KEY_CONFIGURED = "configured";
static const char* KEY_WIFI_SSID = "wifi_ssid";
static const char* KEY_WIFI_PASS = "wifi_pass";
static const char* KEY_CAL_URL = "cal_url";
static const char* KEY_API_SECRET = "api_secret";
static const char* KEY_GH_OWNER = "gh_owner";
static const char* KEY_GH_REPO = "gh_repo";
static const char* KEY_GH_TOKEN = "gh_token";

// ============================================================
// Serial Helpers
// ============================================================

/**
 * Read a line from Serial, blocking until newline received.
 * Echoes characters back. Trims trailing CR/LF.
 */
static String serialReadLine() {
    String input = "";
    while (true) {
        if (Serial.available()) {
            char c = Serial.read();
            if (c == '\n' || c == '\r') {
                // Consume any remaining CR/LF
                delay(10);
                while (Serial.available()) {
                    char next = Serial.peek();
                    if (next == '\n' || next == '\r') {
                        Serial.read();
                    } else {
                        break;
                    }
                }
                Serial.println(); // echo newline
                return input;
            }
            input += c;
            Serial.print(c); // echo
        }
        yield();
    }
}

/**
 * Prompt user for a value. Shows current/default value in brackets.
 * If user enters empty string and allowEmpty is false, re-prompts.
 */
static String serialPrompt(const char* prompt, const char* defaultVal = "", bool allowEmpty = false) {
    while (true) {
        if (strlen(defaultVal) > 0) {
            Serial.printf("%s [%s]: ", prompt, defaultVal);
        } else {
            Serial.printf("%s: ", prompt);
        }

        String input = serialReadLine();
        input.trim();

        if (input.isEmpty()) {
            if (strlen(defaultVal) > 0) {
                return String(defaultVal);
            }
            if (allowEmpty) {
                return "";
            }
            Serial.println("  (Eingabe erforderlich)");
            continue;
        }
        return input;
    }
}

// ============================================================
// Public API
// ============================================================

bool configInit() {
    prefs.begin(NVS_NAMESPACE, true); // read-only
    bool exists = prefs.getBool(KEY_CONFIGURED, false);

    if (exists) {
        String ssid = prefs.getString(KEY_WIFI_SSID, "");
        String pass = prefs.getString(KEY_WIFI_PASS, "");
        String url = prefs.getString(KEY_CAL_URL, "");
        String secret = prefs.getString(KEY_API_SECRET, "");
        String ghOwner = prefs.getString(KEY_GH_OWNER, "");
        String ghRepo = prefs.getString(KEY_GH_REPO, "");
        String ghToken = prefs.getString(KEY_GH_TOKEN, "");

        strncpy(currentConfig.wifiSsid, ssid.c_str(), sizeof(currentConfig.wifiSsid) - 1);
        strncpy(currentConfig.wifiPassword, pass.c_str(), sizeof(currentConfig.wifiPassword) - 1);
        strncpy(currentConfig.calendarUrl, url.c_str(), sizeof(currentConfig.calendarUrl) - 1);
        strncpy(currentConfig.apiSecret, secret.c_str(), sizeof(currentConfig.apiSecret) - 1);
        strncpy(currentConfig.githubOwner, ghOwner.c_str(), sizeof(currentConfig.githubOwner) - 1);
        strncpy(currentConfig.githubRepo, ghRepo.c_str(), sizeof(currentConfig.githubRepo) - 1);
        strncpy(currentConfig.githubToken, ghToken.c_str(), sizeof(currentConfig.githubToken) - 1);

        configLoaded = true;
    }

    prefs.end();
    return exists;
}

const DeviceConfig& configGet() {
    return currentConfig;
}

bool configExists() {
    prefs.begin(NVS_NAMESPACE, true);
    bool exists = prefs.getBool(KEY_CONFIGURED, false);
    prefs.end();
    return exists;
}

void configRunSetupWizard() {
    Serial.println();
    Serial.println("╔══════════════════════════════════════════════╗");
    Serial.println("║   Familienkalender - Ersteinrichtung         ║");
    Serial.println("╚══════════════════════════════════════════════╝");
    Serial.println();
    Serial.println("Bitte die folgenden Werte eingeben.");
    Serial.println("Leere Eingabe uebernimmt den Wert in [Klammern].");
    Serial.println();

    // WiFi
    Serial.println("── WiFi ──────────────────────────────────────");
    String ssid = serialPrompt("SSID");
    String pass = serialPrompt("Passwort");

    // Backend API
    Serial.println();
    Serial.println("── Backend API ───────────────────────────────");
    String url = serialPrompt("Kalender URL (Lambda Function URL)");
    String secret = serialPrompt("API Secret (Bearer Token)");

    // GitHub OTA
    Serial.println();
    Serial.println("── GitHub OTA Updates ────────────────────────");
    String ghOwner = serialPrompt("GitHub Owner (User/Org)");
    String ghRepo = serialPrompt("GitHub Repository", "family_calendar_v2");
    String ghToken = serialPrompt("GitHub Token (leer fuer public Repos)", "", true);

    // Confirm
    Serial.println();
    Serial.println("── Zusammenfassung ───────────────────────────");
    Serial.printf("  WiFi SSID:    %s\n", ssid.c_str());
    Serial.printf("  WiFi Pass:    %s\n", "****");
    Serial.printf("  Kalender URL: %s\n", url.c_str());
    Serial.printf("  API Secret:   %s\n", "****");
    Serial.printf("  GitHub:       %s/%s\n", ghOwner.c_str(), ghRepo.c_str());
    Serial.printf("  GitHub Token: %s\n", ghToken.isEmpty() ? "(keiner)" : "****");
    Serial.println();

    String confirm = serialPrompt("Speichern? (j/n)", "j");
    if (confirm != "j" && confirm != "J" && confirm != "y" && confirm != "Y") {
        Serial.println("Abgebrochen. Starte Setup erneut...");
        Serial.println();
        configRunSetupWizard(); // Recursive retry
        return;
    }

    // Save to NVS
    prefs.begin(NVS_NAMESPACE, false); // read-write
    prefs.putString(KEY_WIFI_SSID, ssid);
    prefs.putString(KEY_WIFI_PASS, pass);
    prefs.putString(KEY_CAL_URL, url);
    prefs.putString(KEY_API_SECRET, secret);
    prefs.putString(KEY_GH_OWNER, ghOwner);
    prefs.putString(KEY_GH_REPO, ghRepo);
    prefs.putString(KEY_GH_TOKEN, ghToken);
    prefs.putBool(KEY_CONFIGURED, true);
    prefs.end();

    // Update in-memory config
    strncpy(currentConfig.wifiSsid, ssid.c_str(), sizeof(currentConfig.wifiSsid) - 1);
    strncpy(currentConfig.wifiPassword, pass.c_str(), sizeof(currentConfig.wifiPassword) - 1);
    strncpy(currentConfig.calendarUrl, url.c_str(), sizeof(currentConfig.calendarUrl) - 1);
    strncpy(currentConfig.apiSecret, secret.c_str(), sizeof(currentConfig.apiSecret) - 1);
    strncpy(currentConfig.githubOwner, ghOwner.c_str(), sizeof(currentConfig.githubOwner) - 1);
    strncpy(currentConfig.githubRepo, ghRepo.c_str(), sizeof(currentConfig.githubRepo) - 1);
    strncpy(currentConfig.githubToken, ghToken.c_str(), sizeof(currentConfig.githubToken) - 1);
    configLoaded = true;

    Serial.println();
    Serial.println("Konfiguration gespeichert!");
    Serial.println();
}

void configClear() {
    prefs.begin(NVS_NAMESPACE, false);
    prefs.clear();
    prefs.end();
    memset(&currentConfig, 0, sizeof(currentConfig));
    configLoaded = false;
    Serial.println("[Config] NVS geloescht. Neustart fuer Setup...");
}
