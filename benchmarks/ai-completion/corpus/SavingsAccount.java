import module java.base;

class Account {

    private final String id;
    private final String owner;

    Account(String id, String owner) {
        this.id = id;
        this.owner = owner;
    }

    String id() {
        return id;
    }

    String owner() {
        return owner;
    }
}

public class SavingsAccount extends Account {

    private final double interestRate;

    public SavingsAccount(String id, String owner, double interestRate) {
        Objects.requireNonNull(id, "id must not be null");
        if (interestRate < 0 || interestRate > 0.2) {
            throw new IllegalArgumentException("Unsupported interest rate: " + interestRate);
        }
        /*|*/
        this.interestRate = interestRate;
    }

    public double interestRate() {
        return interestRate;
    }

    public BigDecimal yearlyInterest(BigDecimal balance) {
        return balance.multiply(BigDecimal.valueOf(interestRate)).setScale(2, RoundingMode.HALF_EVEN);
    }
}
