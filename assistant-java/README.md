# Experimenteller Java-25-Assistent

Die Implementierung unter `assistant-java/` ist ein experimenteller Java-Klon des Python-FastAPI-Backends. Die Python-Version bleibt bis auf Weiteres die massgebliche Referenzimplementierung und das Standardbackend. Weder QWC2 noch Docker Compose werden automatisch auf Java umgestellt. Eine spätere Ablösung ist nicht Bestandteil dieses Auftrags.

Die Portierung folgt dem FastAPI-Stand `abdfa1fcf6ad16219c2a080fd5aa2e3cab4efc58` auf `main`.

Java 25 und Avaje Jex 3.6 bedienen die drei HTTP-Routen über den JDK HttpServer. Avaje JSON Core 3.16 liest und schreibt dynamische JSON-Nachrichten. Jex bringt Avaje AppLog 1.2 transitiv mit. Der JDK HttpClient ruft die festen Infomaniak- und Solothurner Endpunkte auf. Virtuelle Threads übernehmen die HTTP-Verarbeitung und die begrenzten Leseoperationen.

`Assistant.java` nimmt HTTP-Anfragen entgegen, spricht mit Infomaniak und
schreibt die Diagnostik. `Intents.java` erstellt den Modellkontext und prüft
die Absichtserklärung, ihre Suchargumente und Verweise auf frühere Treffer.
`ChatTurn.java` führt die Aufträge einer Nachricht der Reihe nach aus und
liefert Ergebnisse, Rückfragen und Kartenbefehle. Der Browser sendet pro
Nachricht einen Chat-POST und führt die Kartenbefehle in QWC2 aus.

`Geo.java` liest Suche, Layerdetails, Adressen und Datenpunkte. Ein
automatischer Zoom braucht einen eindeutigen, vollständigen Objekttreffer
mit gültiger LV95-Bounding-Box. Die fünf Leseoperationen stehen auch
über `/api/v1/geo` zur Verfügung.

`shared/limits.json` enthält die gemeinsamen Grenzen für Text, Verlauf
und Anfragen. `Schemas.java` definiert die Java-seitigen Feldregeln und
Arbeitsgrenzen. JBang übernimmt die Limits und `text-data.json` in die
Artefakte. `text-data.json` liefert HTML-Zeichenreferenzen und Case Folding
aus Unicode 15.0 für dieselbe Textnormalisierung wie in FastAPI.
Quellen und Lizenzen stehen in der Ressource. [WHATWG-Zeichenreferenzen](https://html.spec.whatwg.org/entities.json) · [Unicode Case Folding](https://www.unicode.org/Public/15.0.0/ucd/CaseFolding.txt)

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
GRAALVM_HOME="$JAVA_HOME" jbang export native -O "$out/assistant-native" assistant-java/Assistant.java
"$out/assistant-native"
```

## Tests

```sh
jbang assistant-java/AssistantTest.java
npm test
```

Mit einem Python-Interpreter, in dem `assistant/requirements.txt` installiert ist, starten dieselben Tests zusätzlich die unveränderte FastAPI-App gegen dieselben lokalen HTTP-Fixtures. Die Tests vergleichen Status und JSON-Werte sowie die Reihenfolge der Modellaufrufe. Zufällige Objektreferenzen und JSON-Formatierung innerhalb der Modellnachrichten werden beim Vergleich normalisiert. Die Java-Tests prüfen zusätzlich, ob `zoom_ref` genau zu einem ausgegebenen Treffer gehört.

```sh
python3.12 -m venv /tmp/qwc-parity-venv
/tmp/qwc-parity-venv/bin/pip install -r assistant/requirements.txt
jbang assistant-java/AssistantTest.java /tmp/qwc-parity-venv/bin/python
```

## Messung

Stand `e21f44f` auf Apple M1, macOS 15.8.1 und arm64. Verwendet wurden
JBang 0.138.0, Temurin 25.0.5.1 und GraalVM CE 25.0.4. Je Variante wurden
fünf Prozessstarts im Abstand von 5 ms auf `/healthz` geprüft.
Der Leerlauf-RSS wurde nach einer Sekunde erfasst. Die erste Geo-Suche
nach Altlasten lief über HTTPS und enthält Netzwerklatenz.
Die Zeiten und der RSS sind Mediane.

| Variante | Artefakt in Bytes | Bis Healthcheck | RSS im Leerlauf | Erste Geo-Suche | Funktionstest |
|---|---:|---:|---:|---:|---|
| Fat-JAR | 363’410 | 241,3 ms | 82,9 MiB | 274,6 ms | PASS |
| Leyden | 32’266’168 | 223,5 ms | 95,8 MiB | 234,5 ms | PASS |
| Native Image | 27’570’512 | 20,6 ms | 20,8 MiB | 154,7 ms | PASS |

Leyden umfasst das portable JAR, seine Bibliotheken und 31’899’648 Bytes
AOT-Cache. Das JVM-Protokoll bestätigte `Opened AOT cache` und
`Using AOT-linked classes: true`. Das Native Image wurde in rund
59 Sekunden gebaut. GraalVM meldete Warnungen zu veralteten Optionen
und zur experimentellen Ressourcenoption.

JBang und alle drei Artefakte starteten lokal. Die Artefakttests prüften alle
fünf Geo-Operationen, die Ablehnung eines alten Chat-Requests und den
Adresszoom mit sichtbaren Diagnosezählern. Die HTTP-Paritätstests ergaben
16 erfolgreiche Gruppen und 79 kontrollierte Vergleiche mit FastAPI.

In QWC2 ging der Adresszoom zur Langendorfstrasse 19b mit einem Chat-POST
und einem Modellaufruf nach `[2605899, 1229278]`. Der kombinierte Auftrag
lud den Altlasten-Layer und zoomte auf Grundstück 2084. **Details** zum
Altlasten-Layer verwendete den direkten Geo-Endpunkt.

## Historische Messung für die frühere Chat-Schnittstelle

Apple M1 mit macOS 15.8.1, Temurin 25.0.5.1, JBang 0.138.0 und Oracle GraalVM 25.0.4+7.1. Median aus fünf Prozessstarts je Variante auf dem laufenden Arbeitsplatzrechner. Ab Prozessstart wurde `/healthz` bis zur ersten erfolgreichen Antwort im Abstand von 5 ms abgefragt. Nach einer Sekunde Leerlauf wurde der RSS mit `ps -o rss=` erfasst. Danach folgte die erste Altlasten-Suche über HTTPS bei geo.so.ch. Ihre Dauer enthält Netzwerklatenz.

| Variante | Artefakt (Bytes) | Bis Healthcheck | RSS im Leerlauf | Erste Geo-Suche | Funktionstest | Build-Aufwand |
|---|---:|---:|---:|---:|---|---|
| Fat-JAR | 351 008 | 673,3 ms | 78,0 MiB | 306,9 ms · HTTP 200 | PASS | JBang-Export |
| Leyden | 32 057 158 | 333,8 ms | 68,6 MiB | 246,5 ms · HTTP 200 | PASS | Portabler Export und HTTP-Training |
| Native Image | 35 330 704 | 45,9 ms | 17,7 MiB | 105,8 ms · HTTP 200 | PASS | GraalVM und Xcode-Werkzeuge, rund 246 s Build |

Bei Leyden umfasst die Grösse das portable JAR mit Bibliotheken und 31 703 040 Bytes AOT-Cache. Das JVM-Protokoll bestätigte `Opened AOT cache` und `Using AOT-linked classes: true`. Der Funktionstest prüfte je Variante Health, alle fünf Geo-Werkzeuge, einen abgelehnten Tool-Aufruf, echtes Function Calling und Browser-Diagnostik über HTTP. Im JBang-Testlauf bestanden 13 Testgruppen und 76 Vergleiche der HTTP-Antworten mit FastAPI.

Im QWC2-Browser lud der kombinierte Auftrag den Kataster der belasteten Standorte und zeigte den ganzen Ausschnitt von Grundstück 2084 in Solothurn. Ein kontrollierter Mehrdeutigkeitsfall hielt den Grundstücksauftrag offen, lud den Layer und löste nach «Altstadt» nur die neue Objektsuche und den Zoom aus. Die Punktabfrage am Klosterplatz 1 ergab `Au`.

## Grenzen

Die Interpretation von Absicht, Negation und Hypothesen folgt wie in FastAPI der Modelldeklaration. Der Server erzwingt die Tool- und Nachweisregeln für den deklarierten Auftrag. Die Tests und die Live-Prüfung sind Stichproben für diese Modellentscheidung.

Syntaktisch ungültiges Modell-JSON führt bei beiden Backends zu
`502 invalidModelResponse`. Java begrenzt die Providerantwort zusätzlich
auf 4 MiB. Die Begrenzung der Solothurner Antwort auf 4 MiB gilt für beide
Backends.
