package dev.turboj.commandj.palette;

import dev.turboj.commandj.core.*;
import java.text.Normalizer;
import java.util.*;

/** Deterministic local fuzzy matching. Index invalidates only when registration changes. */
public final class CommandSearch {
    public record Match(Command command, Availability availability, int score) {}

    private record Indexed(Command command, List<String> terms) {}

    private final CommandRegistry registry;
    private long revision = -1;
    private List<Indexed> index = List.of();

    public CommandSearch(CommandRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public synchronized List<Match> search(String query, CommandContext context, int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Search limit 1–1000");
        if (revision != registry.revision()) {
            index = registry.commands().stream().map(c -> {
                var terms = new ArrayList<String> ();
                terms.add(normalize(c.title()));
                terms.add(normalize(c.id()));
                terms.add(normalize(c.category() + " " + c.title()));
                c.aliases().forEach(a -> terms.add(normalize(a)));
                return new Indexed(c, List.copyOf(terms));
            }).toList();
            revision = registry.revision();
        }
        String q = normalize(Objects.requireNonNullElse(query, "").strip());

        if (q.length() > 256)
            return List.of();
        List<String> recent = registry.recentCommands();
        var result = new ArrayList<Match> ();

        for (var value : index) {
            int score = q.isEmpty() ? 100 : Integer.MAX_VALUE;

            for (String term : value.terms)
                score = Math.min(score, score(q, term));
            if (score == Integer.MAX_VALUE)
                continue;
            int at = recent.indexOf(value.command.id());

            if (at >= 0)
                score -= Math.max(0, 20 - at);
            result.add(new Match(value.command, value.command.available(context), score));
        }
        result.sort(Comparator.comparingInt(Match::score).thenComparing(m -> m.command.title(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(m -> m.command.id()));
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    private static int score(String q, String s) {
        if (q.isEmpty())
            return 100;
        if (q.equals(s))
            return 0;
        if (s.startsWith(q))
            return 20 + s.length() - q.length();
        int exact = s.indexOf(q);

        if (exact >= 0)
            return 60 + exact + s.length() - q.length();
        int at = 0, gaps = 0, last = -1, boundary = 0;

        for (int i = 0; i < q.length(); i++) {
            at = s.indexOf(q.charAt(i), at);
            if (at < 0)
                return Integer.MAX_VALUE;
            if (last >= 0)
                gaps += at - last - 1;
            if (at == 0 || !Character.isLetterOrDigit(s.charAt(at - 1)))
                boundary++;
            last = at++;
        }
        return 120 + gaps * 3 + s.length() - boundary * 4;
    }

    private static String normalize(String value) {
        String text = Normalizer.normalize(value, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder s = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++)
            if (Character.getType(text.charAt(i)) != Character.NON_SPACING_MARK)
                s.append(text.charAt(i));
        return s.toString();
    }
}
