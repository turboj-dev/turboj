# AI completion benchmark — 2026-10-26T12:22:09.739566Z

- Machine: Apple M5, 16 GB, Mac OS X 26.5.1, Java 25.0.4.1
- Endpoint: http://127.0.0.1:11434 (Ollama 0.40.10)
- Corpus: 28 fixtures, 3 runs each, temperature 0.1, num_ctx 8192, max 256 tokens

## Summary

| Model | Cold load | Memory | First text p50 / p95 | Full p50 / p95 | Shown | Match | Compiles (of shown) | Filtered | Wrongly filtered |
|---|---|---|---|---|---|---|---|---|---|
| qwen2.5-coder:0.5b-base | 809 ms | 670 MB (670 MB GPU) | 9 / 38 ms | 77 / 1510 ms | 80/84 | 46/84 (55%) | 19/25 | 3 | 0 |
| qwen2.5-coder:1.5b-base | 1034 ms | 1257 MB (1257 MB GPU) | 15 / 117 ms | 134 / 446 ms | 81/84 | 63/84 (75%) | 26/26 | 0 | 0 |
| hf.co/mradermacher/Qwen2.5-1.5B-GGUF:Q4_K_M | 773 ms | 1257 MB (1257 MB GPU) | 21 / 133 ms | 2903 / 4034 ms | 0/84 | 0/84 (0%) | 0/0 | 22 | 0 |

## JDT baseline (no AI)

JDT completes names, not statements: it is shown with its first proposal and the rank of an expected completion in its list.

| Fixture | Kind | First proposal | Match | Expected at rank | ms |
|---|---|---|---|---|---|
| HelloWorld | statement | `` | no | – | 1515 |
| Calculator | statement | `` | no | – | 181 |
| ArrayStats | line | `` | no | – | 182 |
| BinarySearch | multiline | `` | no | – | 168 |
| WordCounter | statement | `` | no | – | 138 |
| OrderIndex | line | `clear()` | no | – | 127 |
| TaskQueue | token | `clear` | no | 7 of 44 | 133 |
| UserDirectory | line | `` | no | – | 90 |
| SalesReport | line | `` | no | – | 75 |
| Money | block | `` | no | – | 77 |
| Point | line | `` | no | – | 71 |
| Version | block | `` | no | – | 77 |
| PackageManifest | line | `` | no | – | 78 |
| Comparables | block | `` | no | – | 69 |
| LruCache | token | `` | no | – | 68 |
| LineReader | line | `` | no | – | 61 |
| ConfigLoader | statement | `` | no | – | 67 |
| CalculatorTest | statement | `` | no | – | 133 |
| BoundedStackTest | statement | `` | no | – | 91 |
| InventoryService | statement | `` | no | – | 111 |
| ProductController | line | `` | no | – | 93 |
| Shapes | line | `` | no | – | 77 |
| ExprEvaluator | line | `` | no | – | 75 |
| BrowserHistory | token | `clear()` | no | 3 of 53 | 89 |
| PageFetcher | token | `defaultThreadFactory` | no | 5 of 24 | 79 |
| Greeter | statement | `` | no | – | 66 |
| AuditLog | line | `` | no | – | 59 |
| SavingsAccount | statement | `` | no | – | 67 |

JDT first proposal matched 0/28; an expected completion was in its list for 3/28; latency p50 78 ms, p95 182 ms (the first call includes JDT start-up).


## qwen2.5-coder:0.5b-base

| Fixture | Kind | Java | Verdict | Match | Compiles | Full ms | Suggestion (first run) |
|---|---|---|---|---|---|---|---|
| HelloWorld | statement | 17 | ACCEPT | 2/3 | yes | 86 | `System.out.println("Hello World");` |
| Calculator | statement | 17 | ACCEPT | 3/3 | yes | 65 | `return a * b;` |
| ArrayStats | line | 17 | ACCEPT | 3/3 | yes | 77 | `i < values.length; i++` |
| BinarySearch | multiline | 17 | ACCEPT | 1/3 | yes | 313 | `if (sorted[mid] < target) {⏎                low = mid + 1;⏎            } else if (sorted[m…` |
| WordCounter | statement | 17 | ACCEPT | 3/3 | yes | 121 | `counts.put(word, counts.getOrDefault(word, 0) + 1);` |
| OrderIndex | line | 17 | ACCEPT | 0/3 | yes | 91 | `getOrDefault(order.customer, List.of()).add(order);` |
| TaskQueue | token | 17 | ACCEPT | 3/3 | yes | 35 | `isEmpty` |
| UserDirectory | line | 17 | ACCEPT | 2/3 | yes | 56 | `.map(User::email)` |
| SalesReport | line | 17 | NO_INSERTION | 2/3 | – | 146 | `Collectors.groupingBy(Sale::region, Collectors.summingDouble(Sale::amount)` |
| Money | block | 17 | SYNTAX_ERROR | 0/3 | – | 76 | `if (currency == null) {` |
| Point | line | 17 | ACCEPT | 3/3 | yes | 57 | `y - other.y()` |
| Version | block | 17 | ACCEPT | 0/3 | no | 161 | `if (obj == null \|\| getClass() != obj.getClass()) {⏎            return false;⏎        }` |
| PackageManifest | line | 17 | ACCEPT | 3/3 | yes | 61 | `name, version, deps` |
| Comparables | block | 17 | ACCEPT | 3/3 | yes | 137 | `if (candidate.compareTo(best) > 0) {⏎                best = candidate;⏎            }` |
| LruCache | token | 17 | ACCEPT | 0/3 | no | 41 | `isFull` |
| LineReader | line | 17 | ACCEPT | 3/3 | yes | 90 | `BufferedReader reader = Files.newBufferedReader(path)` |
| ConfigLoader | statement | 17 | ACCEPT | 3/3 | yes | 75 | `throw new UncheckedIOException(e);` |
| CalculatorTest | statement | 17 | ACCEPT | 3/3 | yes | 72 | `assertEquals(42, result);` |
| BoundedStackTest | statement | 17 | ACCEPT | 0/3 | yes | 90 | `assertEquals("a", stack.pop());` |
| InventoryService | statement | 17 | ACCEPT | 0/3 | yes | 97 | `stock.merge(sku, quantity, Integer::sum);` |
| ProductController | line | 17 | ACCEPT | 0/3 | – | 534 | `.orElse(ResponseEntity.notFound().build());⏎    }⏎⏎    @PostMapping⏎    public ResponseEnt…` |
| Shapes | line | 21 | ACCEPT | 3/3 | yes | 98 | `case Rectangle r -> r.width() * r.height();` |
| ExprEvaluator | line | 21 | ACCEPT | 0/3 | no | 1530 | `eval(left) * eval(right);⏎            case Neg(Expr operand) -> -eval(operand);⏎        };…` |
| BrowserHistory | token | 21 | ACCEPT | 3/3 | yes | 85 | `get(visited.size() - 1)` |
| PageFetcher | token | 21 | ACCEPT | 0/3 | no | 719 | `newFixedThreadPool(10)) {⏎            List<Future<String>> futures = new ArrayList<>();⏎  …` |
| Greeter | statement | 25 | ACCEPT | 3/3 | yes | 63 | `System.out.println(greet(name));` |
| AuditLog | line | 25 | ACCEPT | 0/3 | no | 349 | `String user, _) -> user + " logged out";⏎            case Purchase(String user, _, _) -> u…` |
| SavingsAccount | statement | 25 | ACCEPT | 0/3 | no | 107 | `this.id = id;⏎        this.owner = owner;` |

## qwen2.5-coder:1.5b-base

| Fixture | Kind | Java | Verdict | Match | Compiles | Full ms | Suggestion (first run) |
|---|---|---|---|---|---|---|---|
| HelloWorld | statement | 17 | ACCEPT | 0/3 | yes | 127 | `System.out.println("Hello World");` |
| Calculator | statement | 17 | ACCEPT | 3/3 | yes | 140 | `return a * b;` |
| ArrayStats | line | 17 | ACCEPT | 3/3 | yes | 161 | `i < values.length; i++` |
| BinarySearch | multiline | 17 | ACCEPT | 3/3 | yes | 593 | `if (sorted[mid] < target) {⏎                low = mid + 1;⏎            } else if (sorted[m…` |
| WordCounter | statement | 17 | ACCEPT | 3/3 | yes | 203 | `counts.merge(word, 1, Integer::sum);` |
| OrderIndex | line | 17 | ACCEPT | 0/3 | yes | 254 | `computeIfAbsent(order.customer, k -> new ArrayList<>()).add(order);` |
| TaskQueue | token | 17 | ACCEPT | 3/3 | yes | 102 | `isEmpty` |
| UserDirectory | line | 17 | ACCEPT | 3/3 | yes | 135 | `.map(User::email)` |
| SalesReport | line | 17 | ACCEPT | 3/3 | yes | 304 | `Collectors.groupingBy(Sale::region, Collectors.summingDouble(Sale::amount))` |
| Money | block | 17 | ACCEPT | 0/3 | yes | 228 | `Objects.requireNonNull(cents, "cents must not be null");` |
| Point | line | 17 | ACCEPT | 3/3 | yes | 137 | `y - other.y()` |
| Version | block | 17 | ACCEPT | 0/3 | yes | 446 | `if (obj == null \|\| getClass() != obj.getClass()) {⏎            return false;⏎        }⏎ …` |
| PackageManifest | line | 17 | ACCEPT | 3/3 | yes | 141 | `name, version, deps` |
| Comparables | block | 17 | ACCEPT | 3/3 | yes | 288 | `if (candidate.compareTo(best) > 0) {⏎                best = candidate;⏎            }` |
| LruCache | token | 17 | ACCEPT | 3/3 | yes | 126 | `removeEldestEntry` |
| LineReader | line | 17 | ACCEPT | 3/3 | yes | 195 | `BufferedReader reader = Files.newBufferedReader(path)` |
| ConfigLoader | statement | 17 | ACCEPT | 3/3 | yes | 167 | `throw new UncheckedIOException(e);` |
| CalculatorTest | statement | 17 | ACCEPT | 3/3 | yes | 165 | `assertEquals(42, result);` |
| BoundedStackTest | statement | 17 | ACCEPT | 3/3 | yes | 258 | `assertThrows(NoSuchElementException.class, () -> stack.pop());` |
| InventoryService | statement | 17 | ACCEPT | 3/3 | yes | 216 | `stock.merge(sku, -quantity, Integer::sum);` |
| ProductController | line | 17 | ACCEPT | 0/3 | – | 512 | `.orElse(ResponseEntity.notFound().build());⏎    }⏎⏎    @GetMapping("/{id}")⏎    public Res…` |
| Shapes | line | 21 | ACCEPT | 3/3 | yes | 201 | `case Rectangle r -> r.width() * r.height();` |
| ExprEvaluator | line | 21 | ACCEPT | 3/3 | yes | 191 | `eval(left) * eval(right)` |
| BrowserHistory | token | 21 | ACCEPT | 0/3 | yes | 127 | `get(0)` |
| PageFetcher | token | 21 | NO_INSERTION | 0/3 | – | 185 | `newFixedThreadPool(uris.size` |
| Greeter | statement | 25 | ACCEPT | 3/3 | yes | 120 | `IO.println(greet(name));` |
| AuditLog | line | 25 | ACCEPT | 3/3 | yes | 140 | `String user, _` |
| SavingsAccount | statement | 25 | ACCEPT | 3/3 | yes | 171 | `super(id, owner);` |

## hf.co/mradermacher/Qwen2.5-1.5B-GGUF:Q4_K_M

| Fixture | Kind | Java | Verdict | Match | Compiles | Full ms | Suggestion (first run) |
|---|---|---|---|---|---|---|---|
| HelloWorld | statement | 17 | NO_INSERTION | 0/3 | – | 2858 | `⏎'''⏎⏎### 3. 使用 'java.lang.reflect' API⏎⏎虽然 'java.lang.reflect' API 可以用于反射，但它的使用范围有限，且不推荐用…` |
| Calculator | statement | 17 | NO_INSERTION | 0/3 | – | 1160 | `⏎'''⏎⏎In this example, the 'Calculator' class has a 'memory' field that is used to store t…` |
| ArrayStats | line | 17 | SYNTAX_ERROR | 0/3 | – | 2833 | `) {⏎    int max = Integer.MIN_VALUE;⏎    for (int value : values) {⏎        max = Math.max…` |
| BinarySearch | multiline | 17 | NO_INSERTION | 0/3 | – | 3013 | `⏎'''⏎⏎### 2. 使用 'java.util.Arrays.binarySearch' 方法⏎⏎'''java⏎import java.util.Arrays;⏎⏎publ…` |
| WordCounter | statement | 17 | NO_INSERTION | 0/3 | – | 2935 | `⏎'''⏎⏎**Java 8**⏎⏎'''⏎import java.util.Map;⏎import java.util.TreeMap;⏎⏎public class WordCo…` |
| OrderIndex | line | 17 | UNKNOWN_NAME | 0/3 | – | 2990 | `⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi…` |
| TaskQueue | token | 17 | NO_INSERTION | 0/3 | – | 97 | `()` |
| UserDirectory | line | 17 | NO_INSERTION | 0/3 | – | 2945 | `⏎'''⏎⏎### 2. 使用 '@SuppressWarnings' 注解⏎⏎'''java⏎import java.util.ArrayList;⏎import java.ut…` |
| SalesReport | line | 17 | NO_INSERTION | 0/3 | – | 80 | `` |
| Money | block | 17 | UNKNOWN_NAME | 0/3 | – | 3121 | `⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi…` |
| Point | line | 17 | NO_INSERTION | 0/3 | – | 3622 | `⏎'''⏎⏎### 2. 使用 '@NonNull' 注解⏎⏎在 Java 8 及以上版本中，可以使用 '@NonNull' 注解来强制类型安全。⏎⏎'''java⏎import …` |
| Version | block | 17 | NO_INSERTION | 0/3 | – | 3063 | `⏎'''⏎⏎### 3. 使用 '@Override' 注解⏎⏎在 Java 8 及以上版本中，可以使用 '@Override' 注解来明确表示一个方法是重写或覆盖的。这在某些情况…` |
| PackageManifest | line | 17 | NO_INSERTION | 0/3 | – | 122 | `` |
| Comparables | block | 17 | SYNTAX_ERROR | 0/3 | – | 3120 | `⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁…` |
| LruCache | token | 17 | NO_INSERTION | 0/3 | – | 86 | `` |
| LineReader | line | 17 | NO_INSERTION | 0/3 | – | 277 | ` = Files.newBufferedReader(path);⏎	reader = new BufferedReader(new InputStreamReader(file.…` |
| ConfigLoader | statement | 17 | NO_INSERTION | 0/3 | – | 2569 | `⏎'''⏎⏎### Explanation⏎⏎1. **Import Statements**: The 'java.io' package is imported to hand…` |
| CalculatorTest | statement | 17 | UNKNOWN_NAME | 0/3 | – | 2955 | `⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi⏎imdi…` |
| BoundedStackTest | statement | 17 | SYNTAX_ERROR | 0/3 | – | 3176 | `⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁⏎猞猁…` |
| InventoryService | statement | 17 | NO_INSERTION | 0/3 | – | 3747 | `⏎'''⏎⏎### 3. **使用 'Optional' 类**⏎⏎'Optional' 类提供了更灵活的方式来处理可能为空的值。例如，你可以使用 'Optional' 来表示一个…` |
| ProductController | line | 17 | NO_INSERTION | 0/3 | – | 3240 | `⏎'''⏎⏎### 3. **使用 '@RestController' 和 '@RequestMapping' 注解**⏎⏎'''java⏎package com.example.…` |
| Shapes | line | 21 | NO_INSERTION | 0/3 | – | 3849 | `⏎'''⏎⏎### 2. 使用 'sealed' 关键字⏎⏎'''java⏎import java.util.List;⏎⏎public class Shapes {⏎⏎    p…` |
| ExprEvaluator | line | 21 | SYNTAX_ERROR | 0/3 | – | 3338 | `⏎class ExprEvaluator {⏎    public sealed interface Expr permits Num, Add, Mul, Neg {⏎    }…` |
| BrowserHistory | token | 21 | NO_INSERTION | 0/3 | – | 85 | `` |
| PageFetcher | token | 21 | UNKNOWN_NAME | 0/3 | – | 3495 | `() {⏎    return Executors.newFixedThreadPool(10);⏎}⏎/current-java-context/src/main/java/co…` |
| Greeter | statement | 25 | NO_INSERTION | 0/3 | – | 3969 | `⏎'''⏎⏎### 2. **Using 'java.io.File'**⏎⏎If you want to read from a file, you can use 'java.…` |
| AuditLog | line | 25 | NO_INSERTION | 0/3 | – | 113 | `` |
| SavingsAccount | statement | 25 | UNKNOWN_NAME | 0/3 | – | 3485 | `⏎imdi java.base;⏎class Account⏎constructor Account(String id, String owner, )⏎String id()⏎…` |
