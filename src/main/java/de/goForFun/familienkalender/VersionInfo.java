package de.goForFun.familienkalender;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Stellt die Anwendungsversion bereit, die zur Build-Zeit aus dem POM injiziert wird.
 */
public final class VersionInfo {

    private static final String VERSION;

    static {
        Properties props = new Properties();
        try (InputStream is = VersionInfo.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException ignored) {
            // fallback to unknown
        }
        VERSION = props.getProperty("app.version", "dev");
    }

    private VersionInfo() {
    }

    public static String getVersion() {
        return VERSION;
    }
}
