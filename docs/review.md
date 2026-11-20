# Phasenreviews

## S0 · Arbeitsgrundlagen

Basis `494da48a7ff1f99c5947a6df89cf8cece0c5a0dc`, geprüft `58f375729da1a72f1528eccb89ac17ae22b450d2`. **PASS** mit OpenCode 2.0.28 und `deepseek/deepseek-flash` im sauberen detached Worktree. Diff, Frontmatter und Auffindbarkeit der drei Skills, Links und `.env`-Ignore geprüft. Der Reviewer empfahl diesen Eintrag und konkrete Proxy-Freigaben samt Versionspin bei der Implementierung. Laufzeittests sind für den reinen Dokumentationscommit nicht anwendbar.

## S1 · Solothurner Live-Karte und Katalog

Basis `58f375729da1a72f1528eccb89ac17ae22b450d2`, Erstprüfung `b77c47d77009020c7d80b71e97eed1ec70af6392` mit OpenCode 2.0.28 und `deepseek/deepseek-flash`: **FAIL**. Das Review bestätigte einen Zugriff auf die alte statische Konfiguration über kodierte Pfade und einen Rasterexport, dessen POST der Nur-Lese-Proxy blockiert. Die Korrektur entfernt die alte Konfiguration, URL-basierte Konfigurationswechsel, Tenant-Header und den Rasterexport aus dem anonymen Client. Der Proxy verwendet die kanonischen Live-Konfigurationspfade. Das nächste Review prüft den korrigierten Commit.
