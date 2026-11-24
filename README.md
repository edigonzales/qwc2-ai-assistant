# QWC2-KI-Assistent für Solothurn

Der lokale Kartenclient verwendet die öffentliche Konfiguration, Karten- und
Suchdienste von geo.so.ch. QWC2 stammt aus dem
[Solothurner Referenzprojekt](https://github.com/edigonzales/qwc-kartenkatalog/tree/d945114f3ff7bcb582d4c8ddd9c0f25464dcb455)
und verwendet dessen unveränderte Version `qwc2-lts@2026.0.30`. Die BSD-Lizenz
steht in [frontend/LICENSE](frontend/LICENSE).

## Architektur

Der QWC2-Webserver leitet `POST /assistant-api/v1/chat` an
`POST /api/v1/chat` in FastAPI weiter. Er ist der Same-Origin-Proxy.
FastAPI erkennt den Auftrag mit dem Infomaniak-Modell und fragt die
Solothurner Suche, `weblayers` oder den Data Service direkt ab. Der
Browser verarbeitet die Antwort in der gelieferten Reihenfolge und führt
Kartenbefehle über QWC2 aus. Der Chatverlauf lebt im Browser bis zum Neuladen.

Beim Klick auf **Details** oder **Anzeigen** für einen Kartentreffer liest
der Browser die Layer-Metadaten über `/assistant-api/v1/geo`. QWC2 lädt
Kartenbilder, Layerdefinitionen, Legenden und Objektinformationen über seine
gewohnten Dienste.

### Beispiel «Gehe nach Solothurn zur Langendorfstrasse 19b»

1. Der Browser sendet den Text und die aktuelle Thema-ID einmal an
   `/assistant-api/v1/chat`. Der QWC2-Webserver reicht die Anfrage an
   FastAPI weiter.
2. FastAPI sendet dem Modell den Systemprompt mit allen unterstützten
   Absichten und Beispielen. Im Userprompt steht der eingegebene Text.
   Wenn Kontext vorhanden ist, steht er separat bezeichnet daneben.
   Das Modell soll die gewünschte Absicht und den Suchtext liefern.
   Eine mögliche Modellantwort für `declare_intents` lautet:

   ```json
   {"intents":[{"goal":"zoom_to_result","target":"Solothurn zur Langendorfstrasse 19b","args":{"query":"Langendorfstrasse 19b Solothurn"}}]}
   ```

3. FastAPI prüft die Modellargumente und sucht die Adresse bei `geo.so.ch`.
   Ein vollständiger, eindeutiger, exakter Treffer liefert die Koordinaten.
   FastAPI erstellt daraus den Kartenbefehl und gibt ihn mit den Treffern
   als Antwort auf den ursprünglichen Chat-Aufruf zurück. Der Kartenbefehl
   für diese Adresse lautet:

   ```json
   {"type":"zoom_to_point","coordinates":[2605899,1229278],"crs":"EPSG:2056","target_scale":1000,"label":"Langendorfstrasse 19b, 4500 Solothurn (Adresse)"}
   ```

4. Der Browser prüft, ob das ursprüngliche Kartenthema weiterhin aktiv und
   die Karte bereit ist. QWC2 zentriert die Karte. Der Chat zeigt den
   tatsächlich erreichten Kartenstand.

Bei mehreren unabhängigen Aufträgen erhält der Browser ein Ergebnis je
Auftrag. Ein eindeutiger Auftrag kann bereits ausgeführt werden, während bei
einem mehrdeutigen Auftrag eine Trefferauswahl oder Rückfrage erscheint.

## Lokal starten

Voraussetzungen sind Docker Compose, Internetzugang und für den Host-Modus
Node.js 22.13 oder neuer. Die verwendete Node-Version steht in [.nvmrc](.nvmrc).

Führe die Befehle im Repository-Verzeichnis aus. Der erste Befehl legt bei
einem frischen Checkout die lokale `.env` aus der Vorlage an.

```sh
test -e .env || cp .env.example .env
npm run setup
npm run dev
```

`npm run dev` startet FastAPI in Compose und QWC2 mit Hot Reload auf dem
Host. Strg+C beendet den Host-Prozess.

Für den vollständigen Containerbetrieb den Host-Prozess beenden und
`docker compose up --build` starten. `docker compose down` entfernt die
Container dieses Projekts.

[Karte öffnen](http://127.0.0.1:8082/map/) ·
[Gemeindegrenzen laden](http://127.0.0.1:8082/map/?l=ch.so.agi.gemeindegrenzen)

Die Dienste verwenden Loopback-Ports. Bei Portkonflikten `QWC_PORT` oder
`ASSISTANT_PORT` in der lokalen `.env` anpassen (Standard 8082 und 8083).
Verwende dann auch bei den Prüfbefehlen die angepassten Ports.

Karte, Geo-Endpunkt und Tests funktionieren mit leeren Infomaniak-Werten. Für
den Live-Chat `INFOMANIAK_API_TOKEN`, `INFOMANIAK_PRODUCT_ID` und
`INFOMANIAK_MODEL` in der gitignorierten `.env` setzen. Compose übergibt
diese Werte an FastAPI. Das Modell muss auf dem gewählten Produkt
verfügbar sein und Function Calling unterstützen.
Mit `Qwen/Qwen3.8-27B-FP8` wurden Modellliste, Text und Function Calling
am konfigurierten Testprodukt mit `temperature=0.2` und
`reasoning_effort=none` geprüft. [Infomaniak API](https://developer.infomaniak.com/docs/api/post/2/ai/%7Bproduct_id%7D/openai/v1/chat/completions)

## Karte und Chat

Über **Karte & Werkzeuge → KI-Assistent** öffnet sich die rechte Seitenleiste.
Beispiele für Anfragen:

- «Wo ist meine Karte zentriert und welche thematischen Layer sind sichtbar?»
- «Suche Karten zu Wasser und Grundwasser im Kanton Solothurn.»
- «Suche Hübeli.»
- «Gehe zu Klosterplatz 1 in Solothurn.»
- «Gehe zum Grundstück 123 in Bellach.»
- «Lade den Layer mit den Altlasten und gehe zu Grundstück 2084 in Solothurn.»
- «In welchem Gewässerschutzbereich liegt Klosterplatz 1 in Solothurn?»
- «Mehr Informationen zu Nummer 2.»
- «Zeige Nummer 2 auf der Karte.»
- «Entferne den Layer Gemeindegrenzen.»
- «Zentriere die Karte auf LV95 2607100 Ost und 1228400 Nord im Massstab 1:10000.»

**Details** liest die Metadaten eines Kartentreffers. **Anzeigen** schaltet
einen Layer ein oder zoomt auf einen Objekttreffer. Nummern im Chat beziehen
sich auf die letzte Layerliste. Die Objektsuche verwendet die Volltextfilter
des aktuellen Kartenthemas ohne `foreground`. Nach einem Navigationsbefehl
zoomt QWC2 bei vollständig ausgewertetem, eindeutigem Treffer auf ca. 1:1000
für Adressen oder auf den ganzen Suchausschnitt für Grundstücke. Bei mehreren
Treffern oder einer gekürzten Liste kannst du einen Eintrag direkt auswählen
oder den Befehl mit Ort, Objektart oder Grundbuchkreis präzisieren.
Die Antwort auf eine Rückfrage setzt den offenen Auftrag mit einer frischen
Suche fort. Kartenstand und sichtbare Layer liest QWC2 zum Zeitpunkt, an dem
es den entsprechenden Lesebefehl ausführt.

Eine eindeutige Adressnavigation benötigt einen Modellaufruf. Bei mehreren
Kartentreffern kann FastAPI das Modell zusätzlich um deren Reihung bitten.
Pro Nachricht sind höchstens acht deklarierte Aufträge, drei Karten- oder
Objektsuchen und vier Modellaufrufe möglich. Die Objektsuche liefert bis zu
25 und die Kartentrefferliste bis zu zehn Einträge.
[Frühere Suchmessung mit zehn Fachfragen](docs/search-quality.md)

## Einstiegspunkte im Code

- Die Chat-Seitenleiste rendert Nachrichten, Treffer und Direktaktionen. [AIChatPlugin.jsx](frontend/js/plugins/AIChatPlugin.jsx)
- FastAPI prüft Argumente anhand der Tool-Schemas. Der Proxy und das Chat-Formular lesen aus derselben Datei ihre Grössenlimits. [tools.json](shared/tools.json)
- Host- und Containerbetrieb nutzen dieselben Proxy-Routen für Live-Karte, Geo-Werkzeuge und Chat. [remote.cjs](frontend/server/remote.cjs)
- Die Absichtserkennung enthält den Systemprompt, den Userprompt mit Kontext und das Schema für die Modellantwort. [intents.py](assistant/intents.py)
- FastAPI führt die kantonalen Abfragen aus und erstellt die Kartenbefehle. [app.py](assistant/app.py)

## Prüfen

```sh
npm test
curl -fsS http://127.0.0.1:8082/assistant-api/v1/geo \
  -H 'Content-Type: application/json' \
  -d '{"name":"search_layers","args":{"query":"Grundwasser"}}'
curl -fsS 'http://127.0.0.1:8083/healthz'
docker compose exec -T assistant env -u INFOMANIAK_API_TOKEN -u INFOMANIAK_PRODUCT_ID -u INFOMANIAK_MODEL python - < assistant/test_chat.py
```

### Browser-Check mit Live-Chat

1. Öffne die Karte mit konfiguriertem Infomaniak-Zugang und wähle
   **Karte & Werkzeuge → KI-Assistent**.
2. Sende «Gehe nach Solothurn zur Langendorfstrasse 19b». Die Karte wird
   auf `[2605899, 1229278]` in EPSG:2056 zentriert. Im Netzwerk-Tab
   erscheint ein Chat-Aufruf.
3. Sende «Suche Karten zu Wasser und Grundwasser im Kanton Solothurn.»
   Der Chat zeigt eine nummerierte Liste mit bis zu zehn Kartentreffern.
4. Klicke bei einem noch nicht sichtbaren Treffer auf **Details**. Unter
   dem Treffer erscheint die Layer-ID. Klicke dort auf **Anzeigen**.
   Unter **Kartenebenen und Legende** erscheint der Layer als aktiv.
   Bei Gruppen können dort die aktiven Unterlayer erscheinen.
5. Öffne den KI-Assistenten erneut und sende «Entferne Nummer 2», wobei
   du die Nummer des angezeigten Treffers verwendest. Der Chat bestätigt
   die Entfernung und der Layer verschwindet aus Karte und Ebenenbaum.
6. Frage «In welchem Gewässerschutzbereich liegt Klosterplatz 1 in Solothurn?».
   Die Antwort nennt den am aufgelösten Punkt abgefragten Bereich `Au`.

## Debugging

Im Netzwerk-Tab siehst du pro Nachricht einen Aufruf an
`/assistant-api/v1/chat`. Direkte Klicks auf Kartentreffer können
`/assistant-api/v1/geo` verwenden. Modell- und Geo-Aufrufe innerhalb
eines Chat-Zugs laufen in FastAPI.

Setze `ASSISTANT_DEBUG=1` in der lokalen `.env` und übernimm die Einstellung
mit `docker compose up -d assistant`. Aktiviere dann im gewünschten Tab die
Inhaltsansicht in der Browserkonsole und sende eine Frage.

```js
sessionStorage.setItem('qwc-ai-debug', 'content')
```

Aktiviere in der Konsole die Debug- oder Verbose-Ausgabe und filtere nach
`[QWC KI]`. Bei `content` zeigt `chat.backend` die Modellnachrichten und
das letzte Modellergebnis als Vorschau unter `payload.json`.
`payload.truncated: true` markiert eine gekürzte Vorschau. Für Metadaten
verwende `meta`. Mit `sessionStorage.removeItem('qwc-ai-debug')` schaltest
du die Ausgabe aus.

FastAPI schreibt pro Chat- und Geo-Aufruf eine JSON-Zeile auf `stdout`.
Bei Chat-Aufrufen zählen `model_calls` und `geo_calls` die Modellaufrufe
und Geo-Werkzeuge eines Zugs. Interne HTTP-Zugriffe eines Geo-Werkzeugs
sind in `geo_calls` nicht einzeln gezählt. Die `turn_id` verbindet die
Zeile mit der Browserkonsole. Bei aktiviertem Debug enthält die Zeile
zusätzliche Angaben zum letzten Modellaufruf oder zum direkten Geo-Aufruf.

```sh
docker compose logs --no-log-prefix --no-color -f assistant
```

`ASSISTANT_DEBUG_PAYLOAD=1` ergänzt bei aktivem Server-Debug auch auf `stdout`
eine begrenzte Textvorschau. Dafür ist keine aktivierte Browserkonsole nötig.
FastAPI schwärzt den konfigurierten Infomaniak-Token und lässt HTTP-Header
weg. Die Vorschau kann Fragen, Verlauf, Adressen und im Chat eingegebene
Zugangsdaten enthalten. Docker kann diese Inhalte speichern.
Setze beide Variablen auf `0` und führe `docker compose up -d assistant` aus,
um die zusätzlichen Server-Diagnosen abzuschalten.

## Grenzen

Die App liest öffentliche Kartenkonfigurationen, Kartenbilder und WMTS,
Suchtreffer, Layerdefinitionen, Objektinformationen, Legenden und Daten.
Für öffentlichen Betrieb wären Authentifizierung, Missbrauchs- und
Kostenlimits sowie Berechtigungsprüfungen erforderlich.

Die Fachabfrage unterstützt Adressen und LV95-Punkte für direkt abfragbare
Data-Service-Datensätze. Grundstücksflächen und amtliche ÖREB-Auszüge sind
weitere Anwendungsfälle. Die Kartensuche hängt von den publizierten Metadaten
und Suchbegriffen ab. [Reale Prüfungen und Reviewstand](docs/review.md)
