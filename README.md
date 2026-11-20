# QWC2-KI-Assistent für Solothurn

Der lokale Kartenclient verwendet die öffentliche Konfiguration, Karten- und
Suchdienste von geo.so.ch. QWC2 stammt aus dem
[Solothurner Referenzprojekt](https://github.com/edigonzales/qwc-kartenkatalog/tree/d945114f3ff7bcb582d4c8ddd9c0f25464dcb455)
und verwendet dessen unveränderte Version `qwc2-lts@2026.0.30`. Die BSD-Lizenz
steht in [frontend/LICENSE](frontend/LICENSE).

Der unveränderte [Quarkus-Kartenkatalog](https://github.com/edigonzales/kartenkatalog)
lädt den unvollständigen XTF-Entwicklungsstand aus `data/`. Der Kartenclient
erreicht die API unter `/catalog-api/v1/ebenen`. Der FastAPI-Dienst vermittelt
Chat und Function Calling über die OpenAI-kompatible Infomaniak-API.
QWC2 führt die Kartenaktionen im Browser aus. Der Chatverlauf lebt im
Browser und wird beim Neuladen verworfen.

## Lokal starten

Voraussetzungen sind Docker Compose, Internetzugang und für den Host-Modus
Node 22.13 oder neuer.

```sh
npm run setup
npm run dev
```

`npm run dev` startet Quarkus und FastAPI in Compose und QWC2 mit Hot Reload
auf dem Host. Strg+C beendet den Host-Prozess.

Für den vollständigen Containerbetrieb den Host-Prozess beenden und
`docker compose up --build` starten. `docker compose down` entfernt die
Container dieses Projekts.

[Karte öffnen](http://127.0.0.1:8082/map/) ·
[Gemeindegrenzen laden](http://127.0.0.1:8082/map/?l=ch.so.agi.gemeindegrenzen)

Die Dienste verwenden Loopback-Ports. Bei Portkonflikten `QWC_PORT`,
`ASSISTANT_PORT` oder `CATALOG_PORT` in der lokalen `.env` anpassen
(Standard 8082, 8083 und 8084). Eine Vorlage steht in `.env.example`.

Für den Chat `INFOMANIAK_API_TOKEN`, `INFOMANIAK_PRODUCT_ID` und
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
Davon sind höchstens drei kurze Lucene-Abfragen mit je bis zu 20 Treffern
zulässig. Das Modell sortiert bis zu 40 eindeutige Kandidaten und kann bis zu
zehn davon anzeigen. Der Browser prüft die IDs gegen die aktuellen Treffer.
[Suchmessung mit zehn Fachfragen](docs/search-quality.md)

## Prüfen

```sh
npm test
curl -fsS 'http://127.0.0.1:8082/catalog-api/v1/ebenen?q=wasser&sort=relevance'
curl -fsS 'http://127.0.0.1:8082/catalog-api/v1/ebenen/ch.so.agi.gemeindegrenzen'
curl -fsS 'http://127.0.0.1:8083/healthz'
docker compose exec -T assistant env -u INFOMANIAK_API_TOKEN -u INFOMANIAK_PRODUCT_ID -u INFOMANIAK_MODEL python - < assistant/test_chat.py
```

Die öffentliche Live-Anbindung beschränkt sich auf Kartenkonfiguration und
Themes, Kartenbilder und WMTS, Suche, Layerdefinitionen, Feature-Information
und Legenden. Die zugelassenen Routen stehen im gemeinsamen
[Proxy](frontend/server/remote.cjs). Für öffentlichen Betrieb wären
Authentifizierung, Missbrauchs- und Kostenlimits sowie Berechtigungsprüfungen
erforderlich.

Der Entwicklungs-Harvest ist unvollständig. Die Treffer hängen von den
vorhandenen Metadaten und den Suchbegriffen ab. Einzelne Layerdefinitionen
oder Fremddienste können ausfallen. [Reale Prüfungen und Reviewstand](docs/review.md)
