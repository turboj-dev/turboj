# Calculator con Maven

Progetto autonomo per **Java 21 o successivo**, con una classe eseguibile e
**due test JUnit 5**. Il codice è lo stesso della [versione senza Maven](../plain-java/README.md).
Il POM non eredita dalla build di TurboJ e la cartella può essere copiata altrove.

## In TurboJ

1. Apri `pom.xml` con **File → Open**, oppure seleziona questa cartella con
   **Open Folder / Maven Project**.
2. Attendi la risoluzione delle dipendenze. Al primo import JUnit può essere
   scaricato dai repository Maven configurati.
3. Scegli **Test → Run All Tests** e conferma la fiducia nel progetto quando
   richiesta: il risultato atteso è **2 passed, 0 failed**.
4. Apri `src/main/java/dev/turboj/examples/calculator/Calculator.java` e scegli
   **Run → Run** per eseguire il programma.

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
