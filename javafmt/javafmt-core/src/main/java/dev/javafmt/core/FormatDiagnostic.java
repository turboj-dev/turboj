package dev.javafmt.core;

public record FormatDiagnostic(int start, int end, String message) {}
