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
- Quarkus-Kartenkatalog (`edigonzales/kartenkatalog`) bleibt der einzige
  Metadaten- und Suchdienst: `/api/v1/ebenen?q=...&sort=relevance`,
  `/api/v1/ebenen/{id}`. Keine zweite Katalogdatenhaltung oder Suchindex.
- Python/FastAPI ist ausschliesslich schlanker LLM-Orchestrator. Die LLM-API
  wird von Infomaniak bezogen. Browser-Aktionen führt nur QWC2 aus;
  browserseitige Katalog-Tools lesen nur über Same-Origin-Routen.
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
4. Relevante Unit-, API-, Compose- und Browser-Tests wirklich ausführen.
   Mock-Tests ersetzen weder den echten Quarkus-Katalog noch eine echte
   QWC2-Kartenaktion. Ungeprüftes ausdrücklich als ungeprüft deklarieren.
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
