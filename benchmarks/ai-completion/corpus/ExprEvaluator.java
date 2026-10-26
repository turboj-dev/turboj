public class ExprEvaluator {

    public sealed interface Expr permits Num, Add, Mul, Neg {
    }

    public record Num(int value) implements Expr {
    }

    public record Add(Expr left, Expr right) implements Expr {
    }

    public record Mul(Expr left, Expr right) implements Expr {
    }

    public record Neg(Expr operand) implements Expr {
    }

    public static int eval(Expr expr) {
        return switch (expr) {
            case Num(int value) -> value;
            case Add(Expr left, Expr right) -> eval(left) + eval(right);
            case Mul(Expr left, Expr right) -> /*|*/;
            case Neg(Expr operand) -> -eval(operand);
        };
    }

    public static String show(Expr expr) {
        return switch (expr) {
            case Num(int value) -> Integer.toString(value);
            case Add(Expr left, Expr right) -> "(" + show(left) + " + " + show(right) + ")";
            case Mul(Expr left, Expr right) -> show(left) + " * " + show(right);
            case Neg(Expr operand) -> "-" + show(operand);
        };
    }
}
