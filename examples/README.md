# Progetti di esempio

Due versioni della stessa piccola calcolatrice, ciascuna con **due test JUnit 5**:

| Progetto | Apri in TurboJ | Test |
| --- | --- | --- |
| [Senza Maven](plain-java/README.md) | `plain-java/test/dev/turboj/examples/calculator/CalculatorTest.java` | Test → Run All Tests |
| [Con Maven](maven/README.md) | `maven/pom.xml` | Test → Run All Tests oppure Build → Maven Test |

I test verificano somme con numeri positivi e negativi, divisioni con risultato
frazionario e il rifiuto della divisione per zero. Entrambi i progetti contengono
un `main` per provare anche **Run**.

Apri un esempio alla volta. Le cartelle sono indipendenti e usano intenzionalmente
gli stessi nomi di classe per confrontare le due modalità.
