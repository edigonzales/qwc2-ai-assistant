# qwc2-ai-assistant

Lokaler QWC2-Prototyp mit eingebautem KI-Chat. Ziel: echte Kartenaktionen und
Layer-Suche über bestehende Solothurner Dienste. Keine eigene GIS-Fachlogik.

## Architektur und Grenzen

- QWC2-Frontend aus `edigonzales/qwc-kartenkatalog` als bewährte Grundlage;
  den dortigen Code nur im neuen Repository übernehmen/anpassen. Die
  Referenzrepositories niemals verändern. QWC2-LTS-Version vorerst beibehalten.
- Bestehende QWC2 Redux-Actions, LayerTree, `themeLayerRestorer` und
  `addThemeSublayer` verwenden. Keine parallele Layerverwaltung oder direkten
  OpenLayers-Hacks, wenn QWC2 bereits eine Action anbietet.
- Öffentliche Karten-/Konfigurationsdienste von `https://geo.so.ch` wie im
  Referenzprojekt verwenden. Remote-Proxy nur für freigegebene Leseoperationen;
  Cookies und Authorization nicht an Solothurner Dienste weiterreichen.
- FastAPI vermittelt das Modell von Infomaniak und führt fachliche Leseaufrufe
  an den Solothurner Such- und Data Service aus. Der Browser ruft diese Tools
  über einen festen Same-Origin-Endpunkt auf. Kartenaktionen führt QWC2 aus.
- Die kantonale Suche liefert Karten- und Ortstreffer, `weblayers` liefert die
  darstellbaren QWC2-Layer. Gruppen zeigen ihre Unterlayer, Fassaden bleiben
  eine Kartenebene. Fachabfragen verwenden konkrete, vom Data Service
  bestätigte Datensatz-IDs. Keine eigene Katalogdatenhaltung, geratenen IDs
  oder Ableitungen aus Darstellungsmerkmalen.
- Toolnamen und Schemas sind fest und allowlisted. KI-Ausgaben sowie
  Metadaten sind nicht vertrauenswürdig. Toolargumente validieren, Ergebnisse
  nur anhand echter API-Daten/Redux-Zustände bestätigen.
- Der MVP ist lokal und anonym. Keine öffentliche Bereitstellung ohne
  Authentifizierung, Kostenlimit und geeignete Zugriffskontrolle.

## Grundsatz: weniger ist besser

Bevorzuge wenige direkte Dateien, Funktionen und Standardbibliotheken. Keine
MCP/WebMCP-, RAG-, Vektor-, Agent- oder Persistenz-Frameworks, keine
hypothetischen Erweiterungspunkte, abstrakten Providerketten oder doppelten
Zustandsmodelle. Vor Änderungen bestehenden Code und Tests lesen. Drittanbieter-
Lizenzhinweise beibehalten. Nur notwendige Test- und Build-Skripte übernehmen.

## Arbeitsablauf

1. Repository-Root, Branch, Ausgangscommit und Gitstatus prüfen; fremde lokale
   Änderungen nicht überschreiben. Auf einem eigenen Branch arbeiten.
2. Bei QWC2-, Layer-, Karten- und Proxy-Code den Skill
   [qwc2-integration](.agents/skills/qwc2-integration/SKILL.md) anwenden.
3. Vor jedem Phase-Commit
   [simplify-change](.agents/skills/simplify-change/SKILL.md) anwenden.
4. Die kleinste aussagekräftige Menge bestehender Tests nutzen und den
   betroffenen Ablauf mit den echten Diensten und QWC2 prüfen. Zusätzliche
   Tests brauchen einen konkreten Fehlerfall. Ungeprüftes ausdrücklich benennen.
5. Den festgehaltenen Commit in einer unabhängigen OpenCode-2.x-Sitzung mit
   DeepSeek API und Modell `deepseek-flash` unter Anwendung von
   [review-change](.agents/skills/review-change/SKILL.md) prüfen.
   Relevante Findings korrigieren und den neuen Commit erneut reviewen.
6. Tests, überprüften SHA, Review-Resultat und offene Einschränkungen knapp
   in `docs/review.md` dokumentieren. Keine eigene Evidence-Infrastruktur.

## Secrets und Freigaben

- Infomaniak-Token nur serverseitig über lokale, gitignorierte `.env` bzw.
  Laufzeitumgebung (`INFOMANIAK_API_TOKEN`, `INFOMANIAK_PRODUCT_ID`,
  `INFOMANIAK_MODEL`). Keinen echten Schlüssel in Code, Tests, Logs,
  Browserbundle, Commits oder Review-Prompts schreiben.
- DeepSeek-Review-Zugang separat in der lokalen OpenCode-Konfiguration oder
  Nutzerumgebung, niemals im Repository.
- Keine Codex-Cloud-Ausführung. Keine produktiven Deployments. Kein Merge.
  Push eines Arbeitsbranches/PR nur im ausdrücklich beauftragten Rahmen.
