package dev.turboj.refactorj.api;

import dev.turboj.refactorj.api.Model.*;
import java.util.List;

/** A headless semantic workspace; potentially blocking calls belong on a serial worker. */
public interface JavaSemanticWorkspace extends AutoCloseable {
    WorkspaceSnapshot snapshot();
    void synchronize(WorkspaceSnapshot snapshot, Cancellation cancellation);
    Symbol resolve(Location location, Cancellation cancellation);
    List<Location> references(String symbolId, Cancellation cancellation);
    Hierarchy hierarchy(String typeId, Cancellation cancellation);
    List<Symbol> symbols(String query, Cancellation cancellation);
    @Override void close();
}
