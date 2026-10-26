import java.util.ArrayList;
import java.util.List;

public class UserDirectory {

    public record User(String name, String email, boolean active) {
    }

    private final List<User> users = new ArrayList<>();

    public void register(User user) {
        users.add(user);
    }

    public List<String> activeEmails() {
        return users.stream()
                .filter(User::active)
                /*|*/
                .sorted()
                .toList();
    }

    public long inactiveCount() {
        return users.stream().filter(user -> !user.active()).count();
    }
}
