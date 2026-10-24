# Calculator con Maven

Progetto autonomo per **Java 21 o successivo**, con una classe eseguibile e
**due test JUnit 5**. Il codice è lo stesso della [versione senza Maven](../plain-java/README.md).
Il POM non eredita dalla build di TurboJ e la cartella può essere copiata altrove.

## In TurboJ

1. Apri `pom.xml` con **File → Open**, oppure seleziona questa cartella con
   **Open Folder / Maven Project**.
2. Attendi **Dependencies Resolved**: al primo import TurboJ scarica JUnit da
   Maven Central o dal mirror configurato nelle impostazioni Maven. Un'eventuale
   modalità offline scelta esplicitamente rimane attiva.
3. Scegli **Run → Run**: TurboJ trova il `main` della calcolatrice anche lasciando
   aperto il POM. Conferma la fiducia nel progetto alla prima esecuzione.
4. Scegli **Test → Run All Tests**: il risultato atteso è **2 passed, 0 failed**.

Non serve selezionare manualmente i sorgenti né configurare il classpath.
Se un progetto contiene più classi eseguibili, **Run** propone quale avviare.

TurboTest compila ed esegue questi test attraverso il percorso nativo.
**Build → Maven Test** esegue invece esplicitamente il lifecycle Maven.

## Da terminale

Con Maven e JDK 21 o successivo installati, dalla cartella di questo esempio:

```sh
mvn test
java -cp target/classes dev.turboj.examples.calculator.Calculator
```

Dal repository TurboJ puoi utilizzare anche il wrapper già presente:

```sh
./mvnw -f examples/maven/pom.xml test
```

Il programma stampa:

```text
3 + 4 = 7
7 / 2 = 3.5
```
