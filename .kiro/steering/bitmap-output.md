# Bitmap-Ausgabe für e-ink Display

## Display-Spezifikation

- Ausgabegerät: e-ink Display (3-Farben)
- Auflösung: 800 x 480 Pixel
- Farbtiefe: 2 Bit (3 Farben + transparent)

## Erlaubte Farben

Es dürfen ausschließlich diese drei Farben als Vollton verwendet werden:

1. **Transparent** (Index 0) – Hintergrund / weiß auf dem Display
2. **Schwarz** (Index 1) – Text, Linien, Rahmen
3. **Rot** (Index 2) – Hervorhebungen, ganztägige Events, Akzente

Graustufen, Farbverläufe oder Halbtöne sind NICHT erlaubt. Es gibt keine weiteren Farben.

## Raster / Dithering

Falls optisch eine Abstufung oder ein Grauton benötigt wird (z.B. für abgeschwächte Hintergründe oder deaktivierte Elemente), muss diese durch ein **Raster-Pattern** (Dithering) aus den erlaubten Volltonfarben erzeugt werden. Beispiel: Abwechselnd gesetzte und nicht gesetzte Pixel im Schachbrettmuster simulieren einen Grauton.

## Technische Umsetzung (Java)

Das Bild wird als `BufferedImage` mit `IndexColorModel` erzeugt:

```java
new IndexColorModel(2, 3,
    new byte[]{(byte) 255, 0, (byte) 255},  // R: transparent=weiß, schwarz, rot
    new byte[]{(byte) 255, 0, 0},            // G
    new byte[]{(byte) 255, 0, 0},            // B
    0)                                        // transparenter Index
```

## Layout-Bereiche

Das Bild ist in folgende Bereiche aufgeteilt:

- **Header** (oben): Datum rechtsbündig, Trennlinie darunter
- **Tageskalender** (linke Hälfte): Zwei Spalten "Heute" und "Morgen" mit Terminen
  - Spaltenüberschrift rechtsbündig
  - Ganztägige Events: roter Hintergrund mit weißem Text
  - Terminierte Events: Uhrzeit + Badge (Initialen) + Titel
- **Wettervorhersage** (rechts oben): 3 Tage mit Icon und Temperatur
- **Monatskalender** (rechts unten): Kalenderraster mit aktuellem Tag rot hervorgehoben
- **Footer** (unten): "letzte Aktualisierung: ..." rechtsbündig

## Wichtige Regeln

- Kein Anti-Aliasing verwenden (e-ink hat keine Graustufen)
- Schriften müssen ohne Kantenglättung gerendert werden
- Alle grafischen Elemente bestehen ausschließlich aus den drei Volltonfarben
- Transparenter Hintergrund erscheint auf dem e-ink Display als weiß
