String greet(String name) {
    if (name.isBlank()) {
        return "Hello, stranger!";
    }
    return "Hello, " + name.strip() + "!";
}

void main() {
    String name = IO.readln("What is your name? ");
    /*|*/
}
