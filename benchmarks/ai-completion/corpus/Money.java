import java.util.Objects;

public record Money(long cents, String currency) {

    public Money {
        /*|*/
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(cents + other.cents, currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        if (other.cents > cents) {
            throw new IllegalArgumentException("Insufficient amount: " + this + " - " + other);
        }
        return new Money(cents - other.cents, currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
