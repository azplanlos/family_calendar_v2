#ifndef OTA_UPDATE_H
#define OTA_UPDATE_H

/**
 * Check GitHub Releases for a newer firmware version and perform OTA update.
 * 
 * Queries the GitHub API for the latest release tag, compares it against
 * FW_VERSION_STRING, and if newer, downloads the firmware.bin asset and
 * flashes it via ESP32 OTA (Update library).
 * 
 * Returns true if an update was applied (device will reboot).
 * Returns false if no update available or update failed.
 */
bool checkAndPerformOTA();

#endif // OTA_UPDATE_H
