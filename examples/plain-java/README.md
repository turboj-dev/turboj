# Calculator senza Maven

Piccolo progetto Java con **due test JUnit 5**, senza POM o build tool.
TurboJ fornisce il runtime JUnit incluso nell'applicazione: non servono download
per eseguire questi test.

## In TurboJ

1. Usa **File → Open** per aprire
   `test/dev/turboj/examples/calculator/CalculatorTest.java`.
2. Scegli **Test → Run All Tests**: il risultato atteso è **2 passed, 0 failed**.
3. Per provare il programma, apri
   `src/dev/turboj/examples/calculator/Calculator.java` e scegli **Run → Run**.

Puoi anche cercare `Run All Tests` nella Command Palette. Per eseguire un solo
test, posiziona il cursore nel suo metodo e scegli **Run Current Test**.

La cartella `.turboj` dichiara le radici `src` e `test` e mantiene questo esempio
separato dagli altri progetti del repository. Copiala insieme al progetto.

## Da terminale

Per compilare ed eseguire il programma con un JDK installato, dalla cartella
di questo esempio:

```sh
mkdir -p target/classes
javac -d target/classes src/dev/turboj/examples/calculator/Calculator.java
java -cp target/classes dev.turboj.examples.calculator.Calculator
```

Output:

```text
3 + 4 = 7
7 / 2 = 3.5
```

I test usano JUnit: in TurboJ è già incluso; un runner esterno deve fornire le
relative librerie. Per una build autonoma con gestione delle dipendenze, vedi
la [versione Maven](../maven/README.md).
