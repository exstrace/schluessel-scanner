# Schlüssel-Scanner (Android)

Erkennt eingelernte Schlüssel über die Kamera anhand des Barts – Vorder- und Rückseite.

## Funktionen

- **Einlernen:** Seite A (Vorderseite) und Seite B (Rückseite) fotografieren, dazu Bezeichnung, Schließungsnummer, Standort und Notiz erfassen.
- **Erkennen:** Beide Seiten fotografieren → die App vergleicht mit allen gespeicherten Schlüsseln und zeigt
  - grün: eindeutiger Treffer
  - orange: wahrscheinlicher Treffer, bitte prüfen
  - grau: kein passender Schlüssel
  sowie die 5 besten Kandidaten mit Punktzahl.
- **Mitlernen:** Bei einem Kandidaten „Das ist er“ tippen. Die Aufnahme wird als weiteres Foto gespeichert, so wird die Erkennung mit der Zeit besser.
- **Export/Import** (Menü oben rechts): komplette Datenbank als ZIP. Damit lässt sie sich sichern oder an Kollegen weitergeben. Beim Import wird zusammengeführt, gleiche Schlüssel werden aktualisiert.
- Alles läuft **offline**. Die Daten liegen nur im App-Speicher des Handys.

## So funktioniert die Erkennung

1. Das Foto wird auf den Führungsrahmen zugeschnitten (nur der Bart).
2. Danach Graustufen, einheitliche Größe und Kontrastausgleich (CLAHE).
3. **ORB-Merkmale** (OpenCV) erfassen die markanten Punkte der Einschnitte, Fräsungen und Profilrillen.
4. Jede Aufnahme wird mit jedem gespeicherten Foto abgeglichen. Eine **geometrische Prüfung** (RANSAC) zählt nur Punkte, die in derselben räumlichen Anordnung passen.
5. Die Punktzahl ist die Summe beider Seiten. Ob der Schlüssel beim Erkennen „richtig herum“ lag, ist egal, weil beide Zuordnungen geprüft werden.

Die Schwellwerte stehen in `KeyMatcher.kt` (`SURE_PER_SIDE`, `LIKELY_PER_SIDE`). Wenn die App zu vorsichtig oder zu großzügig ist, kannst du sie dort nach den ersten Tests anpassen.

**Grenzen:** Baugleiche Schlüssel (gleiche Schließung, gleiche Zuhaltungen) sehen für die Kamera identisch aus. Schlüssel, die sich nur minimal unterscheiden, landen eventuell als „wahrscheinlich“ statt „eindeutig“. Stark spiegelnde Neuschlüssel brauchen mattes, gleichmäßiges Licht.

## APK bauen

### Variante A – über GitHub (ohne Android Studio)

1. Neues **privates** Repository auf GitHub anlegen.
2. Den Inhalt dieses Ordners hochladen. Das geht auch per Browser: „Add file → Upload files“, dabei den Ordner `.github` nicht vergessen.
3. Der Workflow „APK bauen“ startet automatisch (Reiter **Actions**). Er braucht ca. 5 Minuten.
4. Im fertigen Lauf unten bei **Artifacts** `SchluesselScanner-APK` herunterladen, entpacken und `app-debug.apk` aufs Handy kopieren.
5. Auf dem Handy öffnen und die Installation aus unbekannten Quellen erlauben.

### Variante B – Android Studio

1. Ordner in Android Studio öffnen („Open“) und den Gradle-Sync abwarten.
2. Handy per USB anschließen (Entwickleroptionen → USB-Debugging) und auf ▶ klicken,
   oder: *Build → Build App Bundle(s)/APK(s) → Build APK(s)*.

Voraussetzungen: Android 7.0 oder neuer, ARM-Gerät (alle gängigen Handys).

## Tipps für gute Fotos

- Den Schlüssel flach auf einen ruhigen, einfarbigen, matten Untergrund legen, z. B. weißes Papier.
- Die Kamera parallel zum Schlüssel halten, etwa 10–15 cm Abstand. Zum Scharfstellen auf den Bart tippen.
- Pro Seite 2–3 Fotos einlernen (Detailansicht → „Fotos ergänzen“).
- Beim Einlernen und Erkennen möglichst ähnliche Bedingungen schaffen (Untergrund, Licht).

## Projektstruktur

```
app/src/main/java/de/ric/schluessel/
  MainActivity.kt     Liste, Suche, Einlernen, Export/Import
  CaptureActivity.kt  Kamera mit Führungsrahmen, Seite A → Seite B
  ResultActivity.kt   Abgleich, Ergebnis, „Das ist er“
  DetailActivity.kt   Stammdaten bearbeiten, Fotos verwalten
  KeyMatcher.kt       Bildverarbeitung + Vergleich (OpenCV)
  KeyRepository.kt    Speicherung (JSON + Fotos), ZIP-Export/-Import
  KeyGuideView.kt     Rahmen-Overlay
```
