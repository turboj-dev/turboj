import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpClients {
static HttpResponse<String> fetch(String url, String token) throws Exception {
HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).version(HttpClient.Version.HTTP_2).build();
HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).header("Accept", "application/json").timeout(Duration.ofSeconds(10)).GET().build();
return client.send(request, HttpResponse.BodyHandlers.ofString());
}

static String summary(StringBuilder builder, String name, int count) {
return builder.append("name=").append(name).append(", count=").append(count).append(", average=").append(count == 0 ? 0 : 100.0 / count).toString();
}
}
