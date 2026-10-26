import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PageFetcher {

    private final HttpClient client = HttpClient.newHttpClient();

    public String fetch(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    public List<String> fetchAll(List<URI> uris) throws InterruptedException, ExecutionException {
        try (ExecutorService executor = Executors./*|*/()) {
            List<Future<String>> futures = new ArrayList<>();
            for (URI uri : uris) {
                futures.add(executor.submit(() -> fetch(uri)));
            }
            List<String> bodies = new ArrayList<>();
            for (Future<String> future : futures) {
                bodies.add(future.get());
            }
            return bodies;
        }
    }
}
