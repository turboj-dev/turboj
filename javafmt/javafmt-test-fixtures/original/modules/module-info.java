/** An original JavaFMT module example. */
open module example.format{
requires java.base;
requires transitive java.logging;
exports example.api;
uses java.lang.Runnable;
provides java.lang.Runnable with example.internal.Task;
}
