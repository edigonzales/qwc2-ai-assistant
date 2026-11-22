# QWC2-KI-Assistent für Solothurn

Der lokale Kartenclient verwendet die öffentliche Konfiguration, Karten- und
Suchdienste von geo.so.ch. QWC2 stammt aus dem
[Solothurner Referenzprojekt](https://github.com/edigonzales/qwc-kartenkatalog/tree/d945114f3ff7bcb582d4c8ddd9c0f25464dcb455)
und verwendet dessen unveränderte Version `qwc2-lts@2026.0.30`. Die BSD-Lizenz
steht in [frontend/LICENSE](frontend/LICENSE).

FastAPI vermittelt den Chat an die OpenAI-kompatible Infomaniak-API und
fragt die Solothurner Suche, `weblayers` und den Data Service ab. Der
Browser ruft die Geo-Werkzeuge über `/assistant-api/v1/geo` auf. QWC2
führt Kartenaktionen aus und lädt Legenden und Objektinformationen über
seine gewohnten Dienste. Der Chatverlauf lebt im Browser und wird beim
Neuladen verworfen.

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
- «In welchem Gewässerschutzbereich liegt Klosterplatz 1 in Solothurn?»
- «Mehr Informationen zu Nummer 2.»
- «Zeige Nummer 2 auf der Karte.»
- «Entferne den Layer Gemeindegrenzen.»
- «Zentriere die Karte auf LV95 2607100 Ost und 1228400 Nord im Massstab 1:10000.»

**Details** liest Metadaten zu einem Treffer. **Anzeigen** schaltet den
Treffer direkt auf der Karte ein. Die Nummern beziehen sich auf die zuletzt
angezeigte Liste. QWC2 wählt beim Zentrieren eine unterstützte Zoomstufe.
Für Kartenänderungen gibt der Server pro Nachricht das zu einem klar
erkannten Auftrag passende Kartenwerkzeug frei. Die Beispielsätze zeigen
die unterstützten Formen.

Pro Nachricht sind vier Modellrunden und acht Tool-Aufrufe möglich.
Davon sind höchstens drei kantonale Kartenabfragen mit je bis zu 20 Kandidaten
zulässig. Das Modell sortiert bis zu 40 eindeutige Kandidaten und kann bis zu
zehn davon anzeigen. Der Browser prüft die IDs gegen die aktuellen Treffer.
[Suchmessung mit zehn Fachfragen](docs/search-quality.md)

## Einstiegspunkte im Code

- Die Chat-Seitenleiste rendert Nachrichten, Treffer und Direktaktionen. [AIChatPlugin.jsx](frontend/js/plugins/AIChatPlugin.jsx)
- Browser und FastAPI lesen die Tool-Schemas und Grenzen aus derselben Datei. [tools.json](shared/tools.json)
- Host- und Containerbetrieb nutzen dieselben Proxy-Routen für Live-Karte, Geo-Werkzeuge und Chat. [remote.cjs](frontend/server/remote.cjs)
- FastAPI validiert Toolargumente, liest die kantonalen Dienste und vermittelt Modellaufrufe an Infomaniak. [app.py](assistant/app.py)

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
2. Sende «Suche Karten zu Wasser und Grundwasser im Kanton Solothurn.»
   Der Chat zeigt eine nummerierte Liste mit bis zu zehn Kartentreffern.
3. Klicke bei einem noch nicht sichtbaren Treffer auf **Details**. Unter
   dem Treffer erscheint die Layer-ID. Klicke dort auf **Anzeigen**.
   Unter **Kartenebenen und Legende** erscheint der Layer als aktiv.
   Bei Gruppen können dort die aktiven Unterlayer erscheinen.
4. Öffne den KI-Assistenten erneut und sende «Entferne Nummer 2», wobei
   du die Nummer des angezeigten Treffers verwendest. Der Chat bestätigt
   die Entfernung und der Layer verschwindet aus Karte und Ebenenbaum.
5. Frage «In welchem Gewässerschutzbereich liegt Klosterplatz 1 in Solothurn?».
   Die Antwort nennt den am aufgelösten Punkt abgefragten Bereich `Au`.

## Grenzen

Die öffentliche Live-Anbindung beschränkt sich auf Kartenkonfiguration und
Themes, Kartenbilder und WMTS, Suche, Layerdefinitionen, Feature-Information
und Legenden. Für öffentlichen Betrieb wären Authentifizierung, Missbrauchs-
und Kostenlimits sowie Berechtigungsprüfungen erforderlich.

Die Fachabfrage unterstützt Adressen und LV95-Punkte für direkt abfragbare
Data-Service-Datensätze. Grundstücksflächen und amtliche ÖREB-Auszüge sind
weitere Anwendungsfälle. Die Kartensuche hängt von den publizierten Metadaten
und Suchbegriffen ab. [Reale Prüfungen und Reviewstand](docs/review.md)
