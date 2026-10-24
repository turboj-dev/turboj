package dev.turboj.refactorj.api;

/** An embeddable engine may expose intelligence backed by its same semantic workspace. */
public interface JavaIntelligenceProvider {
    JavaIntelligence intelligence();
}
