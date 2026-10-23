package com.turboj.execution;

/** Duration is the measured program invocation time; abnormal exits include elapsed dispatch time. */
public record RunResult(int exitCode, long durationNanos, String detail) {}
