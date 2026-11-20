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

Basis `66f7b13e3fa695bf86b21768bcb8a28507d5f73b`, Erstprüfung
`cebdf4bf9ad1b708e60e293df6c82840efc4eb39` mit OpenCode 2.0.28 und
`deepseek/deepseek-flash` ergab **FAIL**. Das Review fand Metadatenfragen,
die einen Layer aktivieren konnten, zu enge Erkennung von Trefferpositionen
und die Gleichsetzung von QWC-Layernamen mit Katalog-IDs. Der Reviewer führte
42 Node- und 14 isolierte SDK-Tests aus und prüfte Diff und Übersetzungen.
Die Korrektur bindet klare Metadatenfragen an das Detail-Tool, verwendet
ein gemeinsames Muster für Trefferpositionen und verlangt bei einem blossen
QWC-Layernamen die Katalogauflösung. Explizite Themensuchen brauchen einen
echten Katalogaufruf und eine validierte Trefferliste. Bei zunächst leeren,
erfolgreichen Suchen fordert der Server eine zweite Suchrunde mit einem
Synonym an, sofern das Suchbudget dies erlaubt.

Lokal bestanden 44 Node- und 17 isolierte Python-SDK-Tests, ESLint für den
Chatcode, der Compose-Build und die Compose-Konfigurationsprüfung. Die native
Seitenleiste wurde in Chromium auf Desktop und Mobilgrösse geprüft. Host- und
Compose-Modus verwendeten echte Live-Karte, Quarkus und Infomaniak. Im letzten
Compose-Lauf bestätigten Redux und URL den Kartenzustand, Gemeindegrenzen
ohne Duplikat, Entfernen und LV95-Zentrierung. Die Wassersuche zeigte zehn
Katalogtreffer. Details, Anzeige und Entfernen per Titel oder Trefferposition
wurden geprüft. Direktklicks lösten keinen zusätzlichen Modellaufruf aus.
Unbekannte ID, Abbruch eines laufenden Katalogzugriffs, gestoppter Quarkus
und eine isoliert unterbrochene Infomaniak-Verbindung wurden im Browser
geprüft. Die Dienstkonfiguration wurde danach wiederhergestellt. Die
[Suchmessung](search-quality.md) enthält die tatsächlichen IDs und Ränge
von zehn Fachfragen im unvollständigen Entwicklungs-Harvest.

Nachprüfung von `48ff5873d050a7ca638ce7e01959e8db1ceabf43`: **PASS** mit
OpenCode 2.0.28 und `deepseek/deepseek-flash`, 44 Node- und 17 isolierte
SDK-Tests sowie Gegenproben zu Tool-Auswahl, leerer Suche und Proxy-Pfaden.
Das Review fand, dass die Meldung zu einer abgelehnten Modell-ID eine
später erfolgreich ausgeführte Aktion verdeckte. Die Nachbesserung hebt
diese Meldung auf, wenn dasselbe Tool anschliessend erfolgreich ausgeführt
wurde. Für erkannte Metadatenanfragen sind Kartenänderungen ausgeschlossen.
«Zeige den Layer Gemeindegrenzen» löst wieder die Katalogauflösung aus.
Die Laufzeitparameter wurden mit dem dokumentierten Qwen-Modell geprüft.
Lokal erneut bestanden 44 Node- und 19 isolierte SDK-Tests, ESLint,
Compose-Build, der vollständige Kartenablauf und der Suchlauf mit zehn
Fragen. Ein gezielter Chromium-Test verwendete für die nachgebildete
Fehlsequenz eine kontrollierte Modellantwort, den echten Quarkus-Katalog
und QWC2-Redux. Die Metadaten- und Kartenbefehle wurden separat mit echtem
Infomaniak erneut geprüft.

Nachprüfung von `602dbe4d869938e41f4bc16778abad0889477414`: **FAIL** mit
OpenCode 2.0.28 und `deepseek/deepseek-flash` bei 44 Node- und 19 SDK-Tests.
Die Gegenproben zeigten eine verdeckte Fehlermeldung bei Teilerfolgen,
eine mögliche Kartenänderung bei einer reinen Layerfrage und eine
Lücke bei Namen in Anführungszeichen. Die Nachbesserung erlaubt je Turn
nur das ausdrücklich angeforderte Kartenwerkzeug. Bei Teilerfolgen zeigt
der Chat bestätigte Redux-Ergebnisse aus den tatsächlich ausgeführten
Werkzeugen neben der Fehlermeldung. Layernamen in Anführungszeichen und
«Nummer 2 anzeigen» werden unterstützt.
Lokal bestanden 45 Node- und 20 isolierte SDK-Tests, ESLint und der
Compose-Build. Chromium prüfte den Kartenablauf mit Infomaniak inklusive
«Nummer 2 anzeigen», Desktop und Mobilgrösse. Eine reine Layerfrage und
ein Layername in Anführungszeichen liefen mit dem echten Modell. Zum
Teilerfolg zeigte ein gezielter Test mit vorgegebenen Modellaufrufen einen
echten Quarkus-404 und eine bestätigte Entfernung in QWC2. Die Suchmessung
enthält die Ergebnisse des letzten vollständigen Durchlaufs.
