package com.turboj.execution;

/** One ordered output fragment, delivered on the worker reader thread, never the EDT. */
public record OutputEvent(String text, boolean stderr) {}
