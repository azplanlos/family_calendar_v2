package de.goForFun.familienkalender.outlook;

/** Protokoll-Senke der Graph-Anbindung; Aufrufer adaptieren sie auf den Lambda-Logger. */
public interface OutlookLog {
    void warn(String message);

    void error(String message);
}
