# Phasenreviews

## S0 · Arbeitsgrundlagen

Basis `494da48a7ff1f99c5947a6df89cf8cece0c5a0dc`, geprüft `58f375729da1a72f1528eccb89ac17ae22b450d2`. **PASS** mit OpenCode 2.0.28 und `deepseek/deepseek-flash` im sauberen detached Worktree. Diff, Frontmatter und Auffindbarkeit der drei Skills, Links und `.env`-Ignore geprüft. Der Reviewer empfahl diesen Eintrag und konkrete Proxy-Freigaben samt Versionspin bei der Implementierung. Laufzeittests sind für den reinen Dokumentationscommit nicht anwendbar.

## S1 · Solothurner Live-Karte und Katalog

Basis `58f375729da1a72f1528eccb89ac17ae22b450d2`, Erstprüfung `b77c47d77009020c7d80b71e97eed1ec70af6392` mit OpenCode 2.0.28 und `deepseek/deepseek-flash`: **FAIL**. Das Review bestätigte einen Zugriff auf die alte statische Konfiguration über kodierte Pfade und einen Rasterexport, dessen POST der Nur-Lese-Proxy blockiert. Die Korrektur entfernt die alte Konfiguration, URL-basierte Konfigurationswechsel, Tenant-Header und den Rasterexport aus dem anonymen Client. Der Proxy verwendet die kanonischen Live-Konfigurationspfade. Nachprüfung von `165a0f5ec11a95fe39c5f4617b9979e47b3d3fe7`: **PASS** mit derselben OpenCode-Version und demselben Modell. Der Reviewer führte 25 Tests, Compose-Konfigurationsprüfung, Diff und Gegenproben am Proxy aus. Lokal geprüft: npm-Setup, Host- und Compose-Build/Start, Compose-Neustart, Dienst-Health, reale Katalogsuche, Detail, HEAD und 404, Chromium mit URL-Layer, LayerTree, WMTS-Wechsel, Standardsuche, Feature-Info und Legende. Toolbar-Einträge für deaktivierte Tools und doppelte Schrägstriche bei hypothetischen statischen Konfigurationsdateien blieben als nicht blockierende Hinweise. Das XTF bleibt bytegleich. Der Audit des übernommenen Produktions-Lockfiles meldet 14 Abhängigkeitsbefunde.

## S2 · Karten- und Katalogtools

Basis `165a0f5ec11a95fe39c5f4617b9979e47b3d3fe7`, geprüft `f0364849dd798034df03acfce2ab1f55162e95f4`: **PASS** mit OpenCode 2.0.28 und `deepseek/deepseek-flash`. Reviewer: 31 Tests, Diff, Syntax und QWC-LTS-Actions. Lokal zusätzlich ESLint, Compose-Build und temporärer Chromium-Test mit realem Redux, Quarkus-Suche, Details, unbekannter ID, Gemeindegrenzen, Grundwassergruppe, Bundes-WMS (200), LV95 und verzögerter Layerdefinition beim Themenwechsel. Die Tool-Fabrik gilt pro Nachricht oder Direktklick. Gruppen laden ihre aktuelle Zusammensetzung nach, damit auch einzeln entfernte Unterlayer wiederhergestellt werden können. Die Korrektur behandelt Abbruch beim Lesen, einzelne fehlerhafte Treffer und den kurzzeitig veralteten Extent nach Zoom. Nachprüfung von `44eac18cf3ce839b67dd6f9ffed4a6e819ac1336`: **PASS**, 33 unabhängige Tests und sauberer Diff. Der Chromium-Test und ESLint wurden lokal wiederholt. Die spätere UI muss die Fabrik je Nachricht und Klick aufrufen.

## S3 · FastAPI und Infomaniak

Basis `44eac18cf3ce839b67dd6f9ffed4a6e819ac1336`, geprüft `887c036f45b5beca4bde694876f5d709340b2550`: **PASS** mit OpenCode 2.0.28 und `deepseek/deepseek-flash`. Reviewer: 37 Node-Tests, 10 isolierte SDK-Tests, Compose-Konfigurationsprüfung und Protokoll-Gegenproben. Lokal zusätzlich beide Proxy-Betriebsarten, Modellliste, echter Text und ein echter korrelierter Function-Calling-Rundlauf mit `Qwen/Qwen3.8-27B-FP8` und dem Browser-Kartenzustand EPSG:2056. Der erste HTTP 403 war eine lokal falsch konfigurierte Produkt-ID. Nach Freigabe wurde die gitignorierte Einstellung korrigiert. Die Nachbesserung behandelt nicht konforme Provider-Antworten als `502 invalidModelResponse`. Die Gesamtlänge bleibt zusätzlich zu den Feldgrenzen auf 128 KiB begrenzt. Nachprüfung von `66f7b13e3fa695bf86b21768bcb8a28507d5f73b`: **PASS**, 11 isolierte SDK-Tests. Eine echte Textantwort über den Compose-Proxy wurde zusätzlich nach der Korrektur wiederholt.

## S4 · Nativer Chat und Suchmessung

Phasenbasis `66f7b13e3fa695bf86b21768bcb8a28507d5f73b`. Jede Prüfung lief in einem separaten detached Worktree mit OpenCode 2.0.28 und `deepseek/deepseek-flash`.

| Geprüfter Commit | Urteil | Befunde und Korrekturen |
|---|---|---|
| `cebdf4bf9ad1b708e60e293df6c82840efc4eb39` | FAIL | Metadatenfragen konnten Layer aktivieren. Ordinale waren zu eng erkannt und QWC-Namen wurden mit Katalog-IDs gleichgesetzt. Die Korrektur trennt Lesebefehle und Kartenaktionen, teilt das Muster für Trefferpositionen und löst QWC-Namen über den Katalog auf. |
| `48ff5873d050a7ca638ce7e01959e8db1ceabf43` | PASS | Verifizierte Suchtreffer und ein Synonymversuch bei leeren Lucene-Antworten waren bestätigt. Die Anzeige eines abgelehnten Modellvorschlags konnte eine nachfolgend erfolgreiche Aktion verdecken. |
| `602dbe4d869938e41f4bc16778abad0889477414` | FAIL | Der Versuch, den Fehler auszublenden, konnte einen echten Teilerfolg als vollen Erfolg zeigen. Offene Layerfragen konnten Kartenwerkzeuge erhalten. Die Korrektur zeigt bestätigte Aktionen und Fehlermeldungen separat und gibt Kartenwerkzeuge nur bei erkannten Befehlen frei. |
| `13e967c177b7a449dc29065370505a477183976e` | FAIL | Mehrdeutige Layerübersichten konnten eine Aktion freigeben. Die Korrektur bindet Befehlsverb und Ziel, trennt Layerübersicht und Themensuche und erkennt typografische Anführungszeichen. |
| `6c62d88f81928d7ccdbffaf11221e56d3d1ad88d` | FAIL | Zwei explizite Entfernungsvarianten gingen verloren. «Zeige die Layer auf der Karte» konnte sich noch auf ältere Angaben stützen. Bei parameterlosen Modellaufrufen konnten zusätzliche Felder ausserhalb erzwungener Aufrufe die Verarbeitung abbrechen. |
| `ce32b7f0dde9a999d986a6479cba211c08a35f39` | **PASS** | Beide Entfernungsvarianten und die frische Layerübersicht bestätigt. Parameterlose Modellaufrufe werden vor der Validierung auf `{}` gesetzt, wenn der Provider ein JSON-Objekt geliefert hat. |

Im finalen Review bestanden 45 Node- und 23 isolierte SDK-Tests, beide Diff-Prüfungen und gezielte Gegenproben zu Tool-Freigabe, frischen Daten, Katalog-IDs und Kartenbestätigungen. Freiere Befehlsvarianten können ausserhalb der erkannten Formen liegen. Der Reviewer verwendete Offline-Tests und führte die Live-Prüfungen nicht erneut aus.

Lokal bestanden ESLint, Compose-Build und Compose-Konfigurationsprüfung. Chromium prüfte Host- und Compose-Betrieb, Desktop und Mobilgrösse, echten Kartenzustand, Hinzufügen ohne Duplikat, Entfernen, LV95-Zentrierung, Wassersuche, nummerierte Treffer, Direktklicks ohne Modellaufruf und unbekannte IDs. Die beiden korrigierten Entfernungsvarianten und der vollständige Kartenablauf wurden auf dem geprüften Produktcommit mit echtem Katalog, QWC2 und Infomaniak wiederholt. Für den gemischten 404-/Erfolgsfall steuerte eine vorgegebene Modellantwort einen echten Quarkus-404 und eine echte QWC2-Entfernung an. Abbruch während eines Katalogzugriffs, gestoppter Quarkus und die isoliert unterbrochene Infomaniak-Verbindung wurden im Browser geprüft. Danach wurden die Dienste wiederhergestellt. Die Laufzeitparameter wurden mit `Qwen/Qwen3.8-27B-FP8` geprüft. [Quarkus-Suchmessung mit zehn Fachfragen](https://github.com/edigonzales/qwc2-ai-assistant/blob/ae02592b605ad671d932c024598216afed891da9/docs/search-quality.md)

## Kantonsuche und Punktabfrage

Basis `ae02592b605ad671d932c024598216afed891da9`. OpenCode 2.0.28 mit
`deepseek/deepseek-flash` prüfte `2cf8c7058e2946732d9ac141669071576fc24b40`
und meldete **FAIL**. Allgemeine Suchaufträge und die Klosterplatz-Frage
konnten ohne Toolantwort beantwortet werden. Der Adresszusatz «in» konnte
zudem zu einer leeren Suche führen.

Die Nachprüfung von `259ff937d4f04a72154c7016a142ac73e86b9afc` mit
demselben Modell ergab **PASS**. Explizite Suchaufträge erfordern die
Kartensuche. Der erkannte Adressfall erfordert die Auflösung und bindet die
Fachabfrage an deren Punkt. Die Adresssuche normalisiert «in» vor dem Ort.
Der Erstreview prüfte 40 Node- und 26 Python-Tests, Compose-Konfiguration
und kantonale Live-Antworten. Die Nachprüfung wiederholte die 26 Python-Tests
und prüfte die korrigierte Toolauswahl und Adressnormalisierung. Die
serverseitige Erzwingung erkennt bestimmte Adressformulierungen. Die
Auswahl des fachlich passenden Datensatzes bleibt modellgesteuert.

Lokal bestanden 40 Node- und 26 Python-Tests, gezieltes ESLint, Hoststart
und Compose-Build. Im abschliessenden Compose-Lauf lieferte Chromium bei
allen zehn Suchfragen passende Treffer auf Rang 1. Beide Bundesinventare
erschienen. Die Grundwassergruppe wurde nach dem Detailabruf im Ebenenbaum
angezeigt und per Treffernummer entfernt. Klosterplatz 1 ergab in der
echten Fachabfrage `Au`. Die Geo-API bestätigte ebenfalls die normalisierte
Adresse mit «in» und unterschied einen leeren Treffer von einem nicht
verfügbaren Datensatz. Ein GetFeatureInfo-Aufruf für die Fassade
«Grundnutzung» lieferte weiterhin die Antworten der beiden Kinder.
[Suchmessung](search-quality.md)

## Browser-Debug und FastAPI-Logs

Basis `d45e55af9a36b9ac9f65317e0739a75f14c4b7b2`, geprüft
`5ef626aee460d16d063c13d8914bfb5a68ee3592`. **PASS** mit OpenCode 2.0.28
und `deepseek/deepseek-flash`. Der unabhängige Review führte 40 Node- und
28 Python-Tests aus. Er prüfte die getrennten Freigaben für Browser- und
stdout-Inhalte, die Schwärzung des konfigurierten Tokens, die Proxy-Header
und die unveränderten Standardantworten. Das README beschreibt die Grenzen
der Schwärzung und gekürzte Vorschauen ausdrücklich.

Lokal bestanden die Tests und das gezielte ESLint. Chromium prüfte die
Wassersuche mit echtem Infomaniak und Solothurner Suche, die gemeinsame
Kennung über drei Modellrunden und das Anzeigen einer Layergruppe. Ohne
Textoption standen nur Metadaten auf stdout, obwohl die Modellnachrichten
im Browser sichtbar waren. Mit Textoption erschienen Chat- und
Geo-Vorschauen auf stdout. Ein von Infomaniak abgelehnter Modellaufruf
lieferte im Browser und in den Logs denselben Fehlercode und dieselbe
Kennung. Beide Mermaid-Diagramme wurden mit draw.io gerendert, und die
relativen README-Links wurden geprüft.
