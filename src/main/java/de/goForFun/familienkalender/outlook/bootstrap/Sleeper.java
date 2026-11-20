package de.goForFun.familienkalender.outlook.bootstrap;

import java.time.Duration;

/** Wartefunktion des Polling-Automaten (austauschbar für Tests). */
@FunctionalInterface
interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
