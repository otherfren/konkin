# Refactoring-Plan für Klassen > 800 Zeilen

Dieser Plan beschreibt die notwendigen Schritte, um die Klassen im Projekt `konkin` zu refaktorieren, die die Grenze von 800 Zeilen überschreiten.

## 1. LandingPageController (1708 Zeilen)

Der `LandingPageController` hat zu viele Verantwortlichkeiten: Request-Handling, Datenmapping für Freemarker-Templates, Hilfsfunktionen für Formatierung und Telegram-spezifische Logik.

### Geplante Maßnahmen:
- **Auslagern von Mapping-Logik:** Erstellen eines `LandingPageMapper` (oder einer ähnlichen Komponente), der die Konvertierung von DB-Entitäten in `Map<String, Object>` für die Templates übernimmt (Methoden wie `buildDetailsObject`, `mapApprovalPageData`, `buildAuthChannelsModel` etc.).
- **Auslagern von Formatierungs-Hilfsfunktionen:** Erstellen einer Utility-Klasse `UiFormattingUtils` für statische Methoden wie `formatInstant`, `formatRemaining`, `toStatusClass`, `abbreviateId` etc.
- **Telegram-Logik bündeln:** Die Methoden `handleTelegramApprove`, `handleTelegramUnapprove`, `handleTelegramReset`, `handleTelegramSubmit` und `loadTelegramPageData` in einen spezialisierten `TelegramWebController` oder in den `TelegramService` verschieben.
- **Paging-Hilfsfunktionen:** Statische Hilfsmethoden wie `parsePositiveInt`, `pageMetaFrom` in eine `WebUtils` Klasse auslagern.

## 2. KonkinConfig (1279 Zeilen)

`KonkinConfig` ist eine monolithische Konfigurationsklasse, die Parsing, Validierung und Standardwerte für das gesamte System enthält.

### Geplante Maßnahmen:
- **Aufteilen in Sub-Konfigurationen:** Die inneren Klassen `AgentConfig`, `CoinConfig`, `CoinAuthConfig` in eigene Dateien im Package `io.konkin.config` auslagern.
- **Validierung auslagern:** Erstellen einer `KonkinConfigValidator` Klasse, die die komplexen Validierungsmethoden (`validateConsistency`, `validateBitcoinConfig`, etc.) übernimmt.
- **TOML-Parsing kapseln:** Das Laden und Parsen der TOML-Struktur in eine `KonkinConfigLoader` Klasse verschieben, sodass `KonkinConfig` primär ein Datenhalter (POJO/Record) bleibt.
- **Hilfsfunktionen für Parsing:** Methoden wie `parseDuration`, `parseHumanDurationOrNull`, `parseIntOrDefault` in eine `ConfigUtils` Klasse verschieben.

## 3. AuthQueueStore (857 Zeilen)

Der `AuthQueueStore` enthält alle Datenbankzugriffe für das Approval-System, inklusive komplexer Filter-Logik und Paging.

### Geplante Maßnahmen:
- **Spezialisierung der Repositories:** Aufteilen in mehrere spezialisierte Klassen:
    - `ApprovalRequestRepository`: Fokus auf `ApprovalRequestRow` und deren Paging/Filterung.
    - `VoteRepository`: Fokus auf `VoteDetail`.
    - `ChannelRepository`: Fokus auf `ApprovalChannelRow`.
    - `HistoryRepository`: Fokus auf `StateTransitionRow` und `ExecutionAttemptDetail`.
- **Query-Builder/Hilfsklasse für Paging:** Die komplexen SQL-Generierungsmethoden für Filter (`pageApprovalRequestsWithFilter`) in eine eigene Komponente auslagern, um den Store übersichtlicher zu halten.
- **SQL-Utilities:** Hilfsmethoden wie `queryCount`, `normalizePageSize`, `toInstant` in eine Basisklasse oder Utility-Klasse auslagern.

## Zeitplan und Vorgehen
Die Refaktorierung sollte schrittweise erfolgen:
1. **Utility-Klassen erstellen:** Zuerst die einfachsten, zustandslosen Hilfsmethoden auslagern.
2. **Controller-Mapping:** Den Controller durch Delegation an einen Mapper verschlanken.
3. **Konfigurations-Splitting:** Die Konfiguration in kleinere Einheiten zerlegen.
4. **Datenbank-Layer:** Die Aufteilung des Stores als letzten Schritt, da dies die meisten Abhängigkeiten betrifft.
