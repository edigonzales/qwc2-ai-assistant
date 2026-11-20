# QWC2-KI-Assistent für Solothurn

Der lokale Kartenclient verwendet die öffentliche Konfiguration, Karten- und
Suchdienste von geo.so.ch. QWC2 stammt aus dem
[Solothurner Referenzprojekt](https://github.com/edigonzales/qwc-kartenkatalog/tree/d945114f3ff7bcb582d4c8ddd9c0f25464dcb455)
und verwendet dessen unveränderte Version `qwc2-lts@2026.0.30`. Die BSD-Lizenz
steht in [frontend/LICENSE](frontend/LICENSE).

Der unveränderte [Quarkus-Kartenkatalog](https://github.com/edigonzales/kartenkatalog)
lädt den unvollständigen XTF-Entwicklungsstand aus `data/`. Der Kartenclient
erreicht die API unter `/catalog-api/v1/ebenen`. Der FastAPI-Dienst stellt in
dieser Phase `/healthz` bereit.

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

## Prüfen

```sh
npm test
curl -fsS 'http://127.0.0.1:8082/catalog-api/v1/ebenen?q=wasser&sort=relevance'
curl -fsS 'http://127.0.0.1:8082/catalog-api/v1/ebenen/ch.so.agi.gemeindegrenzen'
curl -fsS 'http://127.0.0.1:8083/healthz'
```

Die öffentliche Live-Anbindung beschränkt sich auf Kartenkonfiguration und
Themes, Kartenbilder und WMTS, Suche, Layerdefinitionen, Feature-Information
und Legenden. Die zugelassenen Routen stehen im gemeinsamen
[Proxy](frontend/server/remote.cjs). Für öffentlichen Betrieb wären
Authentifizierung, Missbrauchs- und Kostenlimits sowie Berechtigungsprüfungen
erforderlich.
