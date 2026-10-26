public class Account {
private final String owner;
private double balance;
private int transactions;

public Account(String owner) { this(owner, 0); }

public Account(String owner, double openingBalance) {
if (owner == null || owner.isBlank()) throw new IllegalArgumentException("An account needs an owner");
this.owner = owner; this.balance = openingBalance;
}

public void deposit(double amount) {
if (amount <= 0) throw new IllegalArgumentException("Deposit must be positive: " + amount);
balance += amount; transactions++;
}

public boolean withdraw(double amount) {
if (amount > balance) return false;
balance -= amount; transactions++; return true;
}

public double balance() { return balance; }

@Override public String toString() { return "Account[" + owner + ", balance=" + balance + ", transactions=" + transactions + "]"; }
}
