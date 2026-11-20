# Suchmessung im KI-Assistenten

Am 20. November 2026 in Chromium über die native QWC2-Seitenleiste gemessen.
Verwendet wurden der mitgelieferte Entwicklungs-Harvest, die echte Quarkus-API
und Infomaniak mit `Qwen/Qwen3.8-27B-FP8` bei `temperature=0.2` und
`reasoning_effort=none`. Jede Frage begann mit leerem Chatverlauf. Die Tabelle
zeigt den letzten Wiederholungslauf auf dem S4-Produktstand.
Die Erwartungsbereiche wurden vor der Messung anhand von Katalog-IDs festgelegt.
[Infomaniak Chat Completions](https://developer.infomaniak.com/docs/api/post/2/ai/%7Bproduct_id%7D/openai/v1/chat/completions)

| Anfrage | Erwartungsbereich | Kandidaten | Angezeigt | Erster erwarteter Treffer je Bereich |
|---|---|---:|---:|---|
| Suche Karten zu Grundwasserschutzzonen für die Trinkwasserversorgung im Kanton Solothurn. | Gewässerschutz oder Grundwasserschutz der Nutzungsplanung | 3 | 3 | Rang 1 `ch.so.arp.nutzungsplanung.grundwasserschutz` |
| Welche Karten zeigen Überschwemmungsgefahren bei Hochwasser? | Wassergefahren oder Fliesstiefen des Naturgefahrenkatasters | 4 | 4 | Rang 1 `ch.so.afu.naturgefahren.fliesstiefen_30` |
| Suche die Karte der politischen Gemeindegrenzen. | Politische Gemeindegrenzen | 12 | 5 | Rang 1 `ch.so.agi.gemeindegrenzen` |
| Wo ist Strassenverkehrslärm in Solothurn kartiert? | Solothurner Strassenlärm-Daten | 6 | 5 | Rang 1 `ch.so.avt.strassenlaerm` |
| Suche Karten zu offiziellen Wanderwegen. | Kantonale Wanderwege oder Wanderland | 9 | 9 | Rang 1 `ch.so.arp.wanderwege` |
| Welche Karten zeigen Haltestellen von Bus und Bahn? | Solothurner ÖV-Haltestellen | 2 | 2 | Rang 1 `ch.so.avt.oev.haltestellen` |
| Suche digitale Terrainmodelle auf Basis von LiDAR-Aufnahmen. | Solothurner LiDAR-Terrainmodelle | 13 | 10 | Rang 1 `ch.so.agi.lidar.dtm` |
| Welche Karten zeigen Bauzonen und die kommunale Grundnutzung? | Bauzonengrenzen oder Grundnutzung | 20 | 10 | Rang 1 `ch.so.arp.nutzungsplanung.grundnutzung` |
| Suche die landwirtschaftlichen Fruchtfolgeflächen. | Landwirtschaftliche Fruchtfolgeflächen | 2 | 2 | Rang 1 `ch.so.alw.fruchtfolgeflaechen` |
| Welche Karten zeigen Auen und Flachmoore? | Bundesinventar Auen und Flachmoore | 3 | 3 | Rang 1 `ch.bafu.bundesinventare-auen`, Rang 2 `ch.bafu.bundesinventare-flachmoore` |

## Angezeigte Reihenfolgen

- **Grundwasserschutz** `ch.so.arp.nutzungsplanung.grundwasserschutz`, `ch.so.afu.gewaesserschutz`, `ch.so.arp.richtplan.ueberlagernd_flaeche_bestehend`
- **Hochwasser** `ch.so.afu.naturgefahren.fliesstiefen_30`, `ch.so.afu.naturgefahren.fliesstiefen_100`, `ch.so.afu.naturgefahren.fliesstiefen_300`, `ch.so.afu.naturgefahren.fliesstiefen_restgefaehrdung`
- **Gemeindegrenzen** `ch.so.agi.gemeindegrenzen`, `ch.so.agi.grundbuchplan_f.gemeindegrenzen`, `ch.so.agi.grundbuchplan_sw.gemeindegrenzen`, `ch.so.agi.grundbuchplan_f.gemeindegrenzen_projektiert`, `ch.so.agi.grundbuchplan_sw.gemeindegrenzen_projektiert`
- **Strassenlärm** `ch.so.avt.strassenlaerm`, `ch.so.avt.strassenlaermkataster`, `ch.so.avt.strassenlaermflaechen_tag`, `ch.so.avt.strassenlaermflaechen_nacht`, `ch.so.arp.nutzungsplanung.laerm`
- **Wanderwege** `ch.so.arp.wanderwege`, `ch.so.arp.wanderwege_mit_sperrungen_umleitungen`, `ch.astra.wanderland`, `ch.astra.wanderland-sperrungen_umleitungen`, `ch.so.arp.wanderwege.signalisation`, `ch.so.arp.wanderwege.routen`, `ch.so.awjf.waldwanderwege`, `ch.so.awjf.waldwanderwege_wanderwege`, `ch.so.awjf.waldwanderwege_posten`
- **ÖV-Haltestellen** `ch.so.avt.oev.haltestellen`, `ch.so.avt.oev.haltestellen_beschriftung`
- **LiDAR-Terrain** `ch.so.agi.lidar.dtm`, `ch.so.agi.lidar_2014.dtm`, `ch.bl.agi.lidar_2018.dtm`, `ch.so.agi.lidar_2019.dtm`, `ch.so.agi.lidar.dtm_relief`, `ch.so.agi.lidar_2014.dtm_relief`, `ch.bl.agi.lidar_2018.dtm_relief`, `ch.so.agi.lidar_2019.dtm_relief`, `ch.so.agi.lidar.dtm_slope`, `ch.so.agi.lidar_2014.dtm_slope`
- **Bauzonen** `ch.so.arp.nutzungsplanung.grundnutzung`, `ch.so.arp.nutzungsplanung.grundnutzung_so`, `ch.so.arp.nutzungsplanung.grundnutzung_ch`, `ch.so.arp.bauzonengrenzen`, `ch.so.arp.stabilisierungsziel_rpg2.bauzonen`, `ch.so.arp.arbeitszonenbewirtschaftung.arbeitszonen`, `ch.so.arp.nutzungsplanung`, `ch.so.arp.richtplan.grundnutzung_richtplankarte`, `ch.so.arp.nutzungsplanung.wald.grundnutzung`, `ch.so.arp.nutzungsplanung.gewaesser.grundnutzung`
- **Fruchtfolgeflächen** `ch.so.alw.fruchtfolgeflaechen`, `ch.so.arp.richtplan.ueberlagernd_flaeche_bestehend`
- **Auen und Flachmoore** `ch.bafu.bundesinventare-auen`, `ch.bafu.bundesinventare-flachmoore`, `ch.so.arp.naturschutzobjekte.flachmoore_regionaler_bedeutung`

## Grenzen der Messung

Die Messung erfasst den Rang der erwarteten IDs für diese zehn Fragen.
Bei jeder Frage lag ein erwarteter Treffer auf Rang 1. Bei der Frage nach
Auen und Flachmooren erschien das zweite erwartete Inventar auf Rang 2.
Die Messung bewertet die übrigen Treffer nicht einzeln. Bei Fruchtfolgeflächen erschien
neben dem erwarteten Layer auch eine allgemeine Richtplanebene. Bei Bauzonen
füllten Varianten der Grundnutzung die Liste. Suchphrasen ohne passende
Katalogmetadaten können auch nach dem Umsortieren keine Treffer liefern.
In einem früheren Lauf blieb die Frage zu Strassenverkehrslärm leer. Nach
der Ergänzung einer zweiten Suchphrase bei leeren Ergebnissen fand der
Wiederholungslauf fünf Treffer, darunter die erwarteten Solothurner Lärmdaten.
Ein früherer Durchlauf mit der Standard-Reasoning-Einstellung erreichte
bei einzelnen Anfragen das Tool-Budget oder das Zeitlimit.
