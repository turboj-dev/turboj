package dev.turboj.refactorj.api;

import dev.turboj.refactorj.api.Model.*;
import java.util.List;

/** Preparation is read-only. Application is delegated to a version-checked workspace transaction. */
public interface RefactorJ extends AutoCloseable {
    JavaSemanticWorkspace workspace();
    List<Descriptor> available(Location location, Cancellation cancellation);
    Plan prepare(Requests.Request request, Cancellation cancellation);
    Validation validate(Plan plan, Cancellation cancellation);
    @Override void close();
}
