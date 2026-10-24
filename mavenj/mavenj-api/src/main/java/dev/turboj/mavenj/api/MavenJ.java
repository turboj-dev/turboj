package dev.turboj.mavenj.api;

import dev.turboj.mavenj.api.MavenModels.*;

/** Blocking headless API. Hosts schedule import/resolution off their UI thread. */
public interface MavenJ extends AutoCloseable {
    Workspace open(ImportRequest request, Cancellation cancellation);

    default Workspace open(ImportRequest request) {
        return open(request, Cancellation.NONE);
    }

    default Workspace refresh(ImportRequest request, Cancellation cancellation) {
        return open(request.refreshing(), cancellation);
    }

    Attachment attachment(ImportRequest request, String moduleId, Coordinates artifact,
            String classifier, Cancellation cancellation);

    @Override
    void close();
}
