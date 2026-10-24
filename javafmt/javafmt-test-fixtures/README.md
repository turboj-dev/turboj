# JavaFMT original source corpus

These deliberately irregular Java files were written for JavaFMT. They are
original MIT-licensed fixtures, not copied from Sun, OpenJDK or another formatter.
They exercise Java 25 syntax and protected text rather than defining a style by
their current appearance. Golden canonical output belongs to the core tests.

The module and package examples live in separate directories to avoid pretending
this corpus is one buildable Java module. Individual ordinary examples use only
JDK APIs and can be compiled independently. `CompactMain.java` uses Java 25 compact
source-file syntax; none of these fixtures requires preview features.
