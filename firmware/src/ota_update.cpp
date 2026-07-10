/**
 * OTA Update via GitHub Releases
 *
 * Flow:
 * 1. Query GitHub API: GET /repos/{owner}/{repo}/releases/latest
 * 2. Parse the tag_name (e.g. "v1.2.0") and compare against FW_VERSION_STRING
 * 3. If newer, find the firmware.bin asset download URL
 * 4. Download the binary and flash via ESP32 Update library
 * 5. Reboot into new firmware
 */

#include <Arduino.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Update.h>
#include "config.h"
#include "ota_update.h"

// ============================================================
// Version Parsing & Comparison
// ============================================================

struct SemVer {
    int major;
    int minor;
    int patch;
};

/**
 * Parse a version string like "1.2.3" or "v1.2.3" into components.
 * Returns true on success.
 */
static bool parseSemVer(const String& versionStr, SemVer& ver) {
    String v = versionStr;
    v.trim();
    // Strip leading 'v' or 'V'
    if (v.startsWith("v") || v.startsWith("V")) {
        v = v.substring(1);
    }

    int dot1 = v.indexOf('.');
    if (dot1 < 0) return false;
    int dot2 = v.indexOf('.', dot1 + 1);
    if (dot2 < 0) return false;

    ver.major = v.substring(0, dot1).toInt();
    ver.minor = v.substring(dot1 + 1, dot2).toInt();
    ver.patch = v.substring(dot2 + 1).toInt();
    return true;
}

/**
 * Returns true if 'remote' is newer than 'local'.
 */
static bool isNewer(const SemVer& local, const SemVer& remote) {
    if (remote.major != local.major) return remote.major > local.major;
    if (remote.minor != local.minor) return remote.minor > local.minor;
    return remote.patch > local.patch;
}

// ============================================================
// JSON Helpers (minimal, no library dependency)
// ============================================================

/**
 * Extract a string value for a given key from a JSON string.
 * Very simple parser – works for flat GitHub API responses.
 */
static String jsonGetString(const String& json, const String& key) {
    String searchKey = "\"" + key + "\"";
    int keyIdx = json.indexOf(searchKey);
    if (keyIdx < 0) return "";

    // Find the colon after the key
    int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
    if (colonIdx < 0) return "";

    // Find the opening quote of the value
    int startQuote = json.indexOf('"', colonIdx + 1);
    if (startQuote < 0) return "";

    // Find the closing quote
    int endQuote = json.indexOf('"', startQuote + 1);
    if (endQuote < 0) return "";

    return json.substring(startQuote + 1, endQuote);
}

/**
 * Find the browser_download_url for the asset with the given name
 * in the GitHub release JSON response.
 */
static String findAssetUrl(const String& json, const String& assetName) {
    int searchFrom = 0;
    while (true) {
        // Find next "name" key occurrence
        int nameIdx = json.indexOf("\"name\"", searchFrom);
        if (nameIdx < 0) break;

        // Get the value
        int colonIdx = json.indexOf(':', nameIdx + 6);
        if (colonIdx < 0) break;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) break;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) break;

        String name = json.substring(startQuote + 1, endQuote);

        if (name == assetName) {
            // Found the right asset, now look for browser_download_url nearby
            int urlIdx = json.indexOf("\"browser_download_url\"", endQuote);
            if (urlIdx < 0) break;

            // Make sure we haven't jumped to a different asset block
            // Check that there's no new "name" between our match and the URL
            int nextNameIdx = json.indexOf("\"name\"", endQuote + 1);
            if (nextNameIdx >= 0 && nextNameIdx < urlIdx) {
                // The URL belongs to a different asset, keep searching
                searchFrom = endQuote + 1;
                continue;
            }

            int urlColon = json.indexOf(':', urlIdx + 22);
            if (urlColon < 0) break;
            int urlStart = json.indexOf('"', urlColon + 1);
            if (urlStart < 0) break;
            int urlEnd = json.indexOf('"', urlStart + 1);
            if (urlEnd < 0) break;

            return json.substring(urlStart + 1, urlEnd);
        }

        searchFrom = endQuote + 1;
    }
    return "";
}

// ============================================================
// OTA Implementation
// ============================================================

bool checkAndPerformOTA() {
    Serial.println("[OTA] Checking for firmware updates...");
    Serial.printf("[OTA] Current version: %s\n", FW_VERSION_STRING);

    WiFiClientSecure secureClient;
    secureClient.setInsecure(); // GitHub uses well-known CAs; skip verification for simplicity

    HTTPClient http;

    // Step 1: Query latest release from GitHub API
    String apiUrl = String("https://api.github.com/repos/") +
                    GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    Serial.printf("[OTA] Querying: %s\n", apiUrl.c_str());
    http.begin(secureClient, apiUrl);
    http.addHeader("Accept", "application/vnd.github+json");
    http.addHeader("User-Agent", "ESP32-OTA");
    if (strlen(GITHUB_TOKEN) > 0) {
        http.addHeader("Authorization", String("Bearer ") + GITHUB_TOKEN);
    }

    int httpCode = http.GET();
    if (httpCode != HTTP_CODE_OK) {
        Serial.printf("[OTA] GitHub API request failed: %d\n", httpCode);
        http.end();
        return false;
    }

    String payload = http.getString();
    http.end();

    // Step 2: Parse tag_name and compare versions
    String tagName = jsonGetString(payload, "tag_name");
    if (tagName.isEmpty()) {
        Serial.println("[OTA] Could not parse tag_name from response.");
        return false;
    }
    Serial.printf("[OTA] Latest release tag: %s\n", tagName.c_str());

    SemVer localVer, remoteVer;
    if (!parseSemVer(FW_VERSION_STRING, localVer)) {
        Serial.println("[OTA] Failed to parse local version.");
        return false;
    }
    if (!parseSemVer(tagName, remoteVer)) {
        Serial.println("[OTA] Failed to parse remote version.");
        return false;
    }

    if (!isNewer(localVer, remoteVer)) {
        Serial.println("[OTA] Firmware is up to date.");
        return false;
    }

    Serial.printf("[OTA] New version available: %s -> %s\n", FW_VERSION_STRING, tagName.c_str());

    // Step 3: Find the firmware binary asset URL
    String assetUrl = findAssetUrl(payload, GITHUB_FIRMWARE_ASSET);
    if (assetUrl.isEmpty()) {
        Serial.printf("[OTA] Asset '%s' not found in release.\n", GITHUB_FIRMWARE_ASSET);
        return false;
    }
    Serial.printf("[OTA] Asset URL: %s\n", assetUrl.c_str());

    // Step 4: Download and flash
    Serial.println("[OTA] Downloading firmware...");
    http.begin(secureClient, assetUrl);
    http.addHeader("User-Agent", "ESP32-OTA");
    if (strlen(GITHUB_TOKEN) > 0) {
        http.addHeader("Authorization", String("Bearer ") + GITHUB_TOKEN);
    }
    // GitHub redirects asset downloads, follow redirects
    http.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);

    httpCode = http.GET();
    if (httpCode != HTTP_CODE_OK) {
        Serial.printf("[OTA] Download failed: %d\n", httpCode);
        http.end();
        return false;
    }

    int contentLength = http.getSize();
    if (contentLength <= 0) {
        Serial.println("[OTA] Invalid content length.");
        http.end();
        return false;
    }

    Serial.printf("[OTA] Firmware size: %d bytes\n", contentLength);

    if (!Update.begin(contentLength)) {
        Serial.printf("[OTA] Not enough space for update: %s\n", Update.errorString());
        http.end();
        return false;
    }

    WiFiClient* stream = http.getStreamPtr();
    size_t written = Update.writeStream(*stream);
    Serial.printf("[OTA] Written: %u bytes\n", written);

    if (!Update.end()) {
        Serial.printf("[OTA] Update failed: %s\n", Update.errorString());
        http.end();
        return false;
    }

    http.end();

    if (!Update.isFinished()) {
        Serial.println("[OTA] Update not finished.");
        return false;
    }

    Serial.println("[OTA] Update successful! Rebooting...");
    delay(500);
    ESP.restart();

    // Never reached
    return true;
}
