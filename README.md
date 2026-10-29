# Plaud → Drive (Android)

Kleine Android-App, die Aufnahmen aus der **Plaud-App** (WAV, optional auch MP3/M4A …) automatisch in einen
**Google-Drive-Ordner** hochlädt.

## Wie es funktioniert

```
Plaud-App ──(Teilen/Exportieren)──► "Nach Google Drive sichern" ──► Outbox ──┐
                                                                             ├─► WorkManager ─► Drive API (resumable upload)
Plaud-Exportordner (z. B. Download/Plaud) ──(alle 15 min gescannt)──────────┘
```

Zwei Wege rein:

1. **Teilen-Ziel:** In der Plaud-App bei einer Aufnahme *Teilen → Audio exportieren* → **„Nach Google Drive sichern“**.
   Die Datei wird sofort in den App-Speicher kopiert und im Hintergrund hochgeladen – auch wenn gerade kein Netz da ist.
2. **Ordner-Überwachung:** Du wählst einmal den Ordner, in den du Plaud-Exporte speicherst. Alle 15 Minuten
   (Android-Minimum für Hintergrundjobs) werden neue Dateien gefunden und hochgeladen. Schon hochgeladene Dateien
   werden gemerkt, Dateien, die gerade noch geschrieben werden (< 60 s alt), werden erst im nächsten Lauf geholt.

Uploads laufen über die Drive-REST-API als *resumable upload* (große WAVs werden gestreamt, nicht in den RAM geladen),
mit Scope `drive.file` – die App sieht in deinem Drive **nur** den eigenen Zielordner, nichts anderes.

## Einrichtung (einmalig, ~10 Minuten)

Google verlangt für Drive-Zugriff einen OAuth-Client, der an Paketname + Signatur-Fingerprint gebunden ist:

1. <https://console.cloud.google.com/> → neues Projekt anlegen.
2. *APIs & Dienste → Bibliothek* → **Google Drive API** aktivieren.
3. *OAuth-Zustimmungsbildschirm* → Typ „Extern“, App-Name eintragen, dich selbst unter **Testnutzer** hinzufügen
   (dann ist keine Google-Verifizierung nötig).
4. *Anmeldedaten → Anmeldedaten erstellen → OAuth-Client-ID → Android*
   - Paketname: `com.regez.plauddrive`
   - SHA-1: Fingerprint des Schlüssels, mit dem die APK signiert ist:
     - lokal gebaut (Android Studio, Debug): `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
     - aus GitHub Actions: steht im Build-Log (Schritt „Build“, Zeile `SHA1:`). Damit der Wert stabil bleibt,
       die Secrets `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` setzen
       (sonst signiert jeder CI-Lauf mit einem neuen Zufallsschlüssel).
5. Eine Client-ID muss **nicht** in den Code – Play Services erkennt die App am Paketnamen + SHA-1.

## Bauen & Installieren

- **Android Studio:** Projekt öffnen → Run.
- **Ohne Android Studio:** Jeder Push baut über GitHub Actions eine APK (*Actions → Android APK → Artifacts → plaud-drive-apk*).
  APK aufs Handy laden, Installation aus unbekannten Quellen erlauben.

## Benutzung

1. App öffnen → **Mit Google verbinden** → Konto wählen, Drive-Zugriff erlauben.
2. Optional Zielordnernamen ändern (Standard: `Plaud Recordings`).
3. Entweder aus Plaud teilen, oder **Ordner zum Überwachen wählen** (z. B. `Download/Plaud`).
4. Im Protokoll siehst du jeden Upload; nach Erfolg kommt eine Benachrichtigung.

## Grenzen / ehrliche Einordnung

- Plaud bietet keine offizielle Schnittstelle, um Aufnahmen automatisch „rauszuschieben“. Ein Export pro Aufnahme
  (ein Tippen auf Teilen) bleibt nötig – danach läuft alles automatisch. Die Plaud-App speichert ihre Dateien intern
  (`Android/data/...`), darauf darf seit Android 11 keine andere App zugreifen.
- Voll-automatisch ohne Tippen ginge nur über die (inoffizielle) Cloud-API hinter `web.plaud.ai` auf einem Server
  (z. B. Cronjob, der neue Aufnahmen per Login-Token abholt). Die ist undokumentiert, kann sich jederzeit ändern und
  ist nicht Teil dieser App.
