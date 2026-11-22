# Suchmessung im KI-Assistenten

Am 22. November 2026 in Chromium mit der kantonalen Suche, dem nativen
QWC2-Chat und Infomaniak (`Qwen/Qwen3.8-27B-FP8`, `temperature=0.2`,
`reasoning_effort=none`) geprüft. Der letzte Lauf verwendete den lokalen
Compose-Betrieb auf Stand `259ff93`. Jede Frage begann mit leerem Verlauf.
Die Erwartungsbereiche stammen aus der früheren Quarkus-Messung. Die Ränge
beziehen sich auf die im Browser angezeigte Liste.

| Anfrage | Erwartungsbereich | Angezeigt | Erster erwarteter Treffer |
|---|---|---:|---|
| Suche Karten zu Grundwasserschutzzonen für die Trinkwasserversorgung im Kanton Solothurn. | Gewässer- oder Grundwasserschutz | 2 | 1 · `ch.so.afu.gewaesserschutz` |
| Welche Karten zeigen Überschwemmungsgefahren bei Hochwasser? | Wassergefahren oder Fliesstiefen | 10 | 1 · `ch.so.afu.naturgefahren.fliesstiefen` |
| Suche die Karte der politischen Gemeindegrenzen. | Politische Gemeindegrenzen | 3 | 1 · `ch.so.agi.gemeindegrenzen` |
| Wo ist Strassenverkehrslärm in Solothurn kartiert? | Solothurner Strassenlärm-Daten | 4 | 1 · `ch.so.avt.strassenlaerm` |
| Suche Karten zu offiziellen Wanderwegen. | Kantonale Wanderwege oder Wanderland | 8 | 1 · `ch.so.arp.wanderwege` |
| Welche Karten zeigen Haltestellen von Bus und Bahn? | Solothurner ÖV-Haltestellen | 3 | 1 · `ch.so.avt.oev.haltestellen` |
| Suche digitale Terrainmodelle auf Basis von LiDAR-Aufnahmen. | Solothurner LiDAR-Terrainmodelle | 10 | 1 · `ch.so.agi.lidar.dtm` |
| Welche Karten zeigen Bauzonen und die kommunale Grundnutzung? | Bauzonengrenzen oder Grundnutzung | 5 | 1 · `ch.so.arp.bauzonengrenzen` |
| Suche die landwirtschaftlichen Fruchtfolgeflächen. | Landwirtschaftliche Fruchtfolgeflächen | 1 | 1 · `ch.so.alw.fruchtfolgeflaechen` |
| Welche Karten zeigen Auen und Flachmoore? | Beide Bundesinventare | 4 | 1 · `ch.bafu.bundesinventare-auen`, 2 · `ch.bafu.bundesinventare-flachmoore` |

Im ersten Lauf lieferte das Modell für Hochwasser Begriffe ohne Treffer.
Mit der kürzeren Formulierung `Hochwasser` lieferte die Suche im
abschliessenden Lauf einen passenden Treffer. Weitere Listenplätze wurden
nicht einzeln bewertet.

Im selben Browser wurde die Grundwassergruppe über die Wassersuche gefunden.
Nach dem Öffnen der Details wurden alle drei Unterlayer im Ebenenbaum
angezeigt und die Gruppe per Treffernummer wieder entfernt. Die Frage nach
dem Gewässerschutzbereich am Klosterplatz 1 in Solothurn löste exakt diese
Adresse auf und ergab `typ=Au` aus `ch.so.afu.gewaesserschutz.bereiche`.
