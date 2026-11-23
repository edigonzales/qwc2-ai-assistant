# Experimenteller Java-25-Assistent

Die Implementierung unter `assistant-java/` ist ein experimenteller Java-Klon des Python-FastAPI-Backends. Die Python-Version bleibt bis auf Weiteres die massgebliche Referenzimplementierung und das Standardbackend. Weder QWC2 noch Docker Compose werden automatisch auf Java umgestellt. Eine spätere Ablösung ist nicht Bestandteil dieses Auftrags.

Die Portierung basiert auf dem Remote-HEAD `8b35a19fd05ca63d4c0d7bc9d7003a27746ed65a` von `codex/model-gis-intents`.

Java 25 und Avaje Jex 3.6 bedienen die drei HTTP-Routen über den JDK HttpServer. Avaje JSON Core 3.16 liest und schreibt dynamische JSON-Nachrichten. Jex bringt Avaje AppLog 1.2 transitiv mit. Der JDK HttpClient ruft die festen Infomaniak- und Solothurner Endpunkte auf. Virtuelle Threads übernehmen die HTTP-Verarbeitung und die begrenzten Leseoperationen.

`Assistant.java` validiert das Chatprotokoll und baut die Nachweise pro GIS-Auftrag aus den vom Browser zurückgegebenen Tool-Runden neu auf. Das Modell deklariert die Aufträge in der ersten Runde. Nummerierte Layer, Kartenaktionen und Punktabfragen müssen zum jeweiligen Auftrag und seinen Nachweisen passen. Offene Aufträge kommen als `pending` zurück und lassen sich mit `resume` präzisieren. QWC2 führt Kartenaktionen aus und meldet deren Ergebnis.

`Geo.java` implementiert `search_layers`, `get_layer_details`, `resolve_location`, `query_features` und `search_objects`. Die Objektsuche lädt die Filter je Thema aus `themes.json` und hält diese 30 Sekunden im Speicher. Ein automatischer Zoom benötigt einen eindeutigen Treffer mit gültiger LV95-Bounding-Box und vollständig ausgewerteten Suchresultaten.

`shared/tools.json` ist die gemeinsame Quelle für Tooldefinitionen und Limits. JBang legt die Datei in die Artefakte. `text-data.json` enthält die Namenszuordnungen der HTML-Zeichenreferenzen und das Case Folding aus Unicode 15.0. Sie dienen der gleichen Textnormalisierung wie `html.unescape` und `str.casefold` in der Python-Referenz. Quellen und Lizenzen stehen in der Ressource. [WHATWG-Zeichenreferenzen](https://html.spec.whatwg.org/entities.json) · [Unicode Case Folding](https://www.unicode.org/Public/15.0.0/ucd/CaseFolding.txt)

## Lokal starten

Die Beispiele laufen aus dem Repository-Root. Geprüft mit JBang 0.138.0 und Temurin 25.0.5.1.

```sh
jbang jdk install 25
jbang assistant-java/Assistant.java
```

Der HTTP-Server lauscht standardmässig auf `127.0.0.1:8085`. Ein zusätzlicher QWC2-Hostprozess kann den bestehenden Proxy per Laufzeitvariable auf diesen Port richten.

```sh
ASSISTANT_API_ORIGIN=http://127.0.0.1:8085 QWC_PORT=8086 npm --prefix frontend run start:remote
```

Öffne danach [QWC2 auf Port 8086](http://127.0.0.1:8086/map/) und wähle **Karte & Werkzeuge → KI-Assistent**.

| Variable | Bedeutung |
|---|---|
| `ASSISTANT_JAVA_PORT` | Loopback-Port für Java, Standard 8085 |
| `INFOMANIAK_API_TOKEN`, `INFOMANIAK_PRODUCT_ID`, `INFOMANIAK_MODEL` | Serverseitige Zugangsdaten und Modellname für Chat. Health und Geo funktionieren ohne diese Werte. |
| `ASSISTANT_DEBUG=1` | Provider- und Geo-Diagnostik auf stdout. Für Chat kann der Browser zusätzlich `X-AI-Debug: meta` oder `content` senden. |
| `ASSISTANT_DEBUG_PAYLOAD=1` | Schreibt bei aktivem Server-Debug eine Vorschau von höchstens 16 KiB auf stdout. |

Die Konfiguration wird aus der Prozessumgebung gelesen. Die lokale `.env` kann dafür in einer Shell exportiert werden. Die Diagnose schwärzt den konfigurierten Token. Bei aktivierter Inhaltsvorschau können Fragen, Adressen und andere Chatdaten enthalten sein.

## Fat-JAR

```sh
java25="$(jbang jdk home 25)/bin/java"
out="$(mktemp -d)"
jbang export fatjar -O "$out/assistant.jar" assistant-java/Assistant.java
"$java25" -jar "$out/assistant.jar"
```

## Leyden AOT

Erstelle den portablen Export und starte ihn im Trainingsmodus. Sende die Probeanfragen aus einem zweiten Terminal und beende den Trainingsserver danach mit Strg+C. Für ein Chat-Training mit Function Calling muss der Infomaniak-Zugang beim Start gesetzt sein. Mit dem QWC2-Proxy kannst du beispielsweise den Altlasten-Layer laden und zu Grundstück 2084 in Solothurn navigieren.

```sh
jbang export portable -O "$out/portable/assistant.jar" assistant-java/Assistant.java
"$java25" -XX:AOTCacheOutput="$out/portable/assistant.aot" -Xlog:aot \
  -jar "$out/portable/assistant.jar"
```

```sh
curl -fsS http://127.0.0.1:8085/healthz
curl -fsS http://127.0.0.1:8085/api/v1/geo -H 'Content-Type: application/json' \
  -d '{"name":"search_layers","args":{"query":"Altlasten"}}'
curl -fsS http://127.0.0.1:8085/api/v1/geo -H 'Content-Type: application/json' \
  -d '{"name":"search_objects","theme_id":"default","args":{"query":"2084 Solothurn"}}'
```

Für den Betrieb müssen JDK und JARs zum Training passen. Die JVM protokolliert die geöffnete Cache-Datei. `AOTMode=on` lässt den Start bei einem unbrauchbaren Cache fehlschlagen. [Project Leyden, JEP 514](https://openjdk.org/jeps/514)

```sh
"$java25" -XX:AOTMode=on -XX:AOTCache="$out/portable/assistant.aot" -Xlog:aot \
  -jar "$out/portable/assistant.jar"
```

## GraalVM Native Image

Setze `JAVA_HOME` auf die GraalVM-25-Installation und stelle `native-image` im `PATH` bereit. Auf macOS werden die Xcode Command Line Tools benötigt. JBang übernimmt die Ressourcen und die Native-Image-Optionen aus `Assistant.java`. [GraalVM für macOS](https://www.graalvm.org/jdk25/docs/getting-started/macos/) · [JBang Native Images](https://www.jbang.dev/documentation/jbang/latest/native-images.html)

```sh
export PATH="$JAVA_HOME/bin:$PATH"
native-image --version
jbang export native -O "$out/assistant-native" assistant-java/Assistant.java
"$out/assistant-native"
```

## Tests

```sh
jbang assistant-java/AssistantTest.java
npm test
```

Mit einem Python-Interpreter, in dem `assistant/requirements.txt` installiert ist, starten dieselben Tests zusätzlich die unveränderte FastAPI-App gegen dieselben lokalen HTTP-Fixtures. Die Tests vergleichen Status und JSON-Werte. Zufällige Objektreferenzen und JSON-Formatierung in Toolargumenten werden beim Vergleich normalisiert. Die Java-Tests prüfen zusätzlich, ob `zoom_ref` genau zu einem ausgegebenen Treffer gehört.

```sh
python3 -m venv /tmp/qwc-parity-venv
/tmp/qwc-parity-venv/bin/pip install -r assistant/requirements.txt
jbang assistant-java/AssistantTest.java /tmp/qwc-parity-venv/bin/python
```

## Grenzen

Die Interpretation von Absicht, Negation und Hypothesen folgt wie in FastAPI der Modelldeklaration. Der Server erzwingt die Tool- und Nachweisregeln für den deklarierten Auftrag. Die Tests und die Live-Prüfung sind Stichproben für diese Modellentscheidung.

Eine syntaktisch ungültige JSON-Antwort des Inferenzdienstes liefert in Java `502 invalidModelResponse`. In diesem Fall wirft das Python-SDK bereits vor der Modellvalidierung eine Ausnahme und FastAPI liefert HTTP 500. Java begrenzt zusätzlich die Providerantwort auf 4 MiB. Das bestehende 4-MiB-Limit der Solothurner Antwort bleibt erhalten.
