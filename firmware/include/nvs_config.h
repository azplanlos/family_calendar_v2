#ifndef NVS_CONFIG_H
#define NVS_CONFIG_H

#include <Arduino.h>

// ============================================================
// Device Configuration (stored in NVS flash)
// ============================================================

struct DeviceConfig {
    // WiFi
    char wifiSsid[64];
    char wifiPassword[64];

    // Backend API
    char calendarUrl[256];
    char apiSecret[128];

    // GitHub OTA
    char githubOwner[64];
    char githubRepo[64];
    char githubToken[128]; // empty for public repos
};

/**
 * Initialize NVS and load configuration.
 * Returns true if a valid config was found in flash.
 */
bool configInit();

/**
 * Get pointer to the current device configuration.
 * Only valid after configInit() returns true.
 */
const DeviceConfig& configGet();

/**
 * Check if a valid configuration exists in NVS.
 */
bool configExists();

/**
 * Run the interactive serial setup wizard.
 * Prompts the user for all config values and stores them in NVS.
 * Blocks until configuration is complete.
 */
void configRunSetupWizard();

/**
 * Clear all stored configuration from NVS.
 */
void configClear();

#endif // NVS_CONFIG_H
