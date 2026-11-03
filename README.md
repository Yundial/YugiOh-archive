# YGO Tester

App Android per provare i propri mazzi di Yu-Gi-Oh! contro un avversario virtuale.

- **Archivio mazzi**: crea, modifica, duplica, rinomina, elimina, importa/esporta liste `.ydk`.
- **Ricerca carte** in italiano per nome, per codice ID (le cifre in basso a sinistra) o per codice di stampa (es. `LOB-IT001`).
- **Duelli con le regole ufficiali**: usa il motore di EDOPro (`ocgcore`) con gli script di tutte le carte, quindi ogni effetto funziona davvero.
- **Avversario a 3 livelli**, ognuno con il suo stile:
  - *Facile* – gioca per divertirsi (Acqua, Brancomalvagio, Gagaga)
  - *Medio* – gioca per vincere, combo e Evocazioni dall'Extra (Custode di Tombe, Fedele della Luce, Eroe Malvagio)
  - *Difficile* – prima di ogni mossa simula più sequenze di gioco e sceglie la migliore; nega appena può (Mayakashi, Drago Bianco Occhi Blu, Drago Nero Occhi Rossi)

## Compilare l'APK

Il workflow `.github/workflows/build.yml` compila tutto su GitHub Actions a ogni push:
1. crea un repository (anche privato) e carica questa cartella;
2. apri la scheda **Actions** → *Compila APK* → scarica l'artefatto **YGO-Tester-APK**;
3. installa l'APK sul telefono (consenti le app da origini sconosciute).

L'APK è firmato con la chiave in `keystore/` (sempre la stessa), così gli aggiornamenti si installano sopra la versione precedente.

Il secondo job del workflow (*motore*) compila il motore C++ su Linux e fa giocare tra loro i bot con tutti i mazzi dell'app per verificare che nulla si blocchi.

## Primo avvio

L'app scarica una volta sola (serve internet):
- il database delle regole di EDOPro (BabelCDB, ~8 MB);
- nomi e testi italiani da YGOPRODeck (~20 MB).

Gli script degli effetti si scaricano quando una carta entra in partita e restano salvati; le immagini vengono da YGOPRODeck e restano in cache.

## Struttura

```
app/src/main/cpp/
  third_party/ocgcore/   motore delle regole di EDOPro (AGPL-3.0) + Lua 5.4
  engine/                duello, lettura messaggi, bot a 3 livelli
  jni/                   ponte verso Kotlin
  test/duel_test.cpp     banco di prova da riga di comando (bot contro bot)
app/src/main/java/...    app Kotlin / Jetpack Compose
app/src/main/assets/     mazzi del bot, testi di sistema italiani di EDOPro
tools/                   script per generare i mazzi e provare il motore
```

## Licenze

Il motore `ocgcore` e gli script delle carte (Project Ignis) sono AGPL-3.0: questo progetto è per uso personale.
Yu-Gi-Oh! è un marchio di Konami; le immagini delle carte vengono da YGOPRODeck.
