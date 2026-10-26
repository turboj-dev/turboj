import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AuditLog {

    public sealed interface Event permits Login, Logout, Purchase {
    }

    public record Login(String user, Instant at) implements Event {
    }

    public record Logout(String user, Instant at) implements Event {
    }

    public record Purchase(String user, Instant at, long amountCents) implements Event {
    }

    private final List<Event> events = new ArrayList<>();

    public void append(Event event) {
        events.add(event);
    }

    public static String describe(Event event) {
        return switch (event) {
            case Login(String user, _) -> user + " logged in";
            case Logout(/*|*/) -> user + " logged out";
            case Purchase(String user, _, long amountCents) -> user + " spent " + amountCents + " cents";
        };
    }

    public List<String> lines() {
        return events.stream().map(AuditLog::describe).toList();
    }
}
