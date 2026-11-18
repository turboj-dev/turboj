# TurboJ — stato del progetto e piano unico di sviluppo

**Aggiornamento: 18 novembre 2026. Consegna sorgenti `c7d08485`, correzioni CI `ede0821`/`98002ef`, renderer `ee98ac8`, aspettativa test Intel `48815c8`; companion locale `9cbd9e1f` con patch verificata. Reactor corrente PASS: 976 test / 141 suite, zero skip/failure/error. Tredici controlli nativi, pacchetto/DMG, misure comparabili e sito/export verificati. Rapporto e matrice finali disponibili; CI hosted macOS Intel/Linux/sito PASS su `48815c8` ([run](https://github.com/turboj-dev/turboj/actions/runs/44378326946)). Nessun deploy.**

Questo è il **canovaccio operativo unico**: sommario, stato effettivo, decisioni, attività aggregate, dipendenze e criteri di accettazione. Sostituisce la precedente lista breve e consolida il lavoro disperso nei documenti del progetto. Le specifiche e le evidenze originali restano nei rispettivi percorsi: non vengono eliminate né trasformate retroattivamente in verifiche della versione attuale.

Le nuove attività vanno aggiunte qui, con ID stabile. I documenti specialistici descrivono contratti e risultati; non devono diventare backlog paralleli. Una richiesta in un prompt non prova un'implementazione; un limite storico superato non costituisce un'attività ancora aperta.

## Indice

1. [Sommario e punto di ripartenza](#1-sommario-e-punto-di-ripartenza)
2. [Baseline e stato delle consegne](#2-baseline-e-stato-delle-consegne)
3. [Architettura e capacità disponibili](#3-architettura-e-capacità-disponibili)
4. [Evidenze e documenti superati](#4-evidenze-e-documenti-superati)
5. [Sequenza di lavoro consigliata](#5-sequenza-di-lavoro-consigliata)
6. [Registro unico delle attività](#6-registro-unico-delle-attività)
7. [Verifica e definizione di completamento](#7-verifica-e-definizione-di-completamento)
8. [Manutenzione del canovaccio](#8-manutenzione-del-canovaccio)
9. [Indice delle fonti consolidate](#9-indice-delle-fonti-consolidate)

## 1. Sommario e punto di ripartenza

TurboJ è già un'applicazione Java/Swing con editor Java2D, compilazione ed esecuzione reali, intelligenza Java JDT, refactoring, JUnit, progetti Maven, comandi centralizzati, temi e completamento AI. La demo web ricostruisce sessioni native documentate; non esegue Java nel browser.

L'obiettivo resta **Minimal interface. Maximum Java intelligence.** Il lavoro successivo deve consolidare l'esperienza esistente e completare le parti parziali, preservando tastiera, leggerezza e correttezza semantica.

| Area | Stato rilevato | Prossimo risultato necessario |
| --- | --- | --- |
| Editor e tastiera | Build corrente e flussi automatizzati qualificati; migrazione e audit comandi verificati | Walkthrough fisico/IME/VoiceOver KEY-05 rinviato |
| Quick Fix / Quick Assist | Diagnostiche multiple, selezioni semiaperte, eccezioni, costruttori e lambda verificati; popup nativo finale PASS | Preservare i rifiuti sicuri; nessuna pretesa di parità Eclipse universale |
| First launch e template | Semantica JDT, contesti, import e formattazione verificati nei workflow nativi finali | KEY-05 umano resta rinviato; macro arbitrarie non supportate |
| Output, Tests, Explorer | Console verbatim, Tests separato, `:!`, ridimensionamento, font condiviso e operazioni file | RUN-01/02 chiuse; mantenere i controlli di regressione su target, Save e focus |
| MavenJ / TurboTest / RefactorJ | Runtime e toolchain Java 17 reali, multi-modulo e lifecycle esplicito verificati; recovery e metadati rafforzati | Limiti filesystem dichiarati in REF-01; pacchetto/formatter qualificati |
| Cherry Test | Collezioni, rerun esatti, callback JUnit e repository esterni verificati; inspector/pulsanti nativi finali PASS | Adapter senza callback restano a progressione per fase; inventario universale non inventato |
| TurboAI | AI-13 verificata: trigger selettivi, frequenza limitata, dismiss e precedenza template/JDT | Provider cloud a pagamento separati in AI-05; qualità soggettiva non certificata |
| Formattazione | Default IntelliJ-style su JDT; anche preset JDT e google-java-format | Allineare documentazione; formatter proprietario sospeso |
| Sito e hero | Layout/replay/103 temi verificati; build protetta finale PASS, 312 comandi e quattro PDF aggiornati | Produzione non modificata; hosting e VoiceOver fisico non certificati |
| Distribuzione/ecosistema | Pacchetto ad-hoc/DMG, receipt, matrice sorgenti e note di consegna verificati; storia sibling preservata | CI hosted finale macOS Intel/Linux/sito PASS, inclusi app/DMG e upload; nessuna release notarizzata |

**KEY-03 completata il 17 novembre:** 309 comandi con shortcut nei quattro profili e [PDF completi da quattro pagine](docs/cheatsheets/README.md). Verifica umana host ancora in KEY-05.

**KEY-04/05/06, continuazione del 17 novembre:** matrice di routing per tutti i 309 comandi, AI locale reale nei quattro profili e dialoghi annidati; corretto il bypass degli acceleratori durante IME e aggiunte API accessibili del sorgente, verificate anche dal bridge macOS. [Prove e perimetro](docs/evidence/keyboard-completion-2026-11-17/README.md). Il walkthrough fisico/VoiceOver è rinviato esplicitamente dall’utente. La matrice non certifica gli effetti di ogni callback.

**KEY-04, verifica automatizzata completata il 17 novembre:** tutti i 309 comandi hanno un effetto concreto verificato nel perimetro dichiarato, inclusi i sei rami prima incompleti e apertura del report Cherry nel browser. Verificati attachment reali, parent POM read-only, processor custom con codice generato/eseguito, Apply AI con Keychain, pull Ollama reale su modello già presente e protocolli cloud su fixture HTTPS esplicite; completati import/reset/remove/overwrite dei keymap. PASS: 22 suite native precedenti, 48 asserzioni AI locale e ulteriori cinque controlli nativi; nuovo `./mvnw install -pl app` con 321 test. [Chiusura e limiti](docs/evidence/key04-effects-2026-11-17/boundary-followup/README.md), [registro dei 309 comandi](docs/evidence/key04-effects-2026-11-17/command-outcomes.md). Non certifica ogni input Java/servizio remoto; cloud vendor a pagamento e primo download di un modello non sono simulati come prove live. KEY-05 umano rimane rinviato; verifica live dei vendor cloud tracciata separatamente in AI-05.

**Template e ciclo coverage, 17 novembre:** QFX-03/05 verificati nel perimetro automatizzato; nuovi live template dichiarativi e personalizzabili con catalogo Java IntelliJ di 51 definizioni (limiti macro/contesti in QFX-10). CH-10 verificato con nuovo test scritto da tastiera, Save, Cherry reale e provenance corrente. Registro aggiornato a 312 comandi; i tre nuovi comandi hanno prove native. [Risultati e riproduzione](docs/evidence/live-templates-2026-11-17/README.md). KEY-05 e AI-05 restano separati.

**QFX-04 completata il 16 novembre:** workflow tastiera plain/Maven fino a JUnit verde, shortcut globali nei pannelli inferiori e isolamento modale. [Prove correnti](docs/evidence/qfx04-2026-11-16/README.md). KEY-04 e QFX-03/05 sono state successivamente verificate il 17 novembre; KEY-05 fisico rimane rinviato. Il flusso plain/Maven è nuovamente PASS sul JAR finale P0, senza inferire parità Eclipse universale. CH-01 e JAVA-06 consegnate; CH-02/08/09 completate nel perimetro dichiarato javac/JaCoCo e qualificazione mini/shop/JUG: provenance server, union dei probe, policy per progetto e sidecars/eventi nativi. Il resto del prodotto Cherry rimane distinto nelle righe aperte. Kotlin/Rust/ECJ e output incrementali privi di capture non vengono certificati correnti; è un limite esplicito, non coverage zero. Preservare controlli Cmd+1/Monokai e routing Run.

Il consolidamento iniziale è stato un audit documentale e del codice, senza riesecuzione di build, test o controlli live. Le verifiche successive sono registrate separatamente in §7; non ricertificano automaticamente le evidenze di ottobre.

## 2. Baseline e stato delle consegne

### Versioni e cronologia

| Elemento | Stato rilevato | Interpretazione |
| --- | --- | --- |
| TurboJ sorgenti consegnati | `c7d08485`, seguito da `ede0821` per isolamento CI/fixture | Baseline della sequenza `dd75866`; 743 input del JAR qualificato coincidono con c7d08485. La correzione CI non modifica Java di produzione |
| Maven root | `0.1.0-SNAPSHOT`, JDK 25 | Distinto dal numero del pacchetto macOS |
| Packaging macOS | Script ancora con app-version `1.0.10` | Da riconciliare con prossima consegna; HEAD non è automaticamente distribuito |
| Cherry sibling | Commit locale `9cbd9e1f` sulla base `39af874d`; 16 ancestor non pubblicati preesistenti preservati | Patch dei 15 file pubblicata con TurboJ e verificata sulla base identificata; CLI hash `01004385…37f25`, 423 PASS e due skip Docker. Nessun push della storia sibling |
| JUG Lugano | `8cc859d` verificato il 16 novembre | Suite container completa e comando nativo verificati; AGENTS/ATTIVITA preesistenti preservati |
| Sito | Ultimo deploy documentato: sorgente `294984a`, 25 ottobre; registrazione `bb59258` | Produzione non verificata oggi; push e deploy sono distinti |
| JAR/pacchetto locale corrente | Reactor 976 test / 141 suite e controlli nativi finali PASS | Receipt schema 2, 123 file/99 symlink, sei avvii LaunchServices, 13 controlli nativi e matrice sorgenti verificati. Il JAR del dialogo/P0 resta storico |
| Pacchetto Quick Fix | `target/quick-fix-monokai/TurboJ.app`, JAR del 25 ottobre | Precede cambi successivi: non basta per chiudere una regressione attuale |

Passaggi storici: `802f496` keyboard-first; `997057b` hero; `2ca283f` layout; `32ee964` workflow Eclipse; `61208bc` comando Output/curl; `294984a` flash tema; `db1d4ed` Cherry; `a26e6b7` keymap TurboJ/formatter; `b8f5978` template; `23425cf` TurboAI; `76db7c4` Output/Tests; `fc4bdac` Explorer/risultati.

### Inventario storico del lavoro locale preesistente

Prima di aggiornare questo file: **18 file modificati e 8 non tracciati**. Non sono cambi prodotti dall'audit né automaticamente pronti per un commit unico.

| Gruppo | Percorsi | Continuazione |
| --- | --- | --- |
| Routing e menu | `app/src/main/java/dev/turboj/app/{TurboFrame,MenuLayout}.java`; `commandj/commandj-keymap/src/main/java/dev/turboj/commandj/keymap/BundledKeymaps.java` | Rivedere `run`/`run.context`, Eclipse e AutoRun |
| Smoke modificati | `app/src/test/java/dev/turboj/app/{CherryNativeSmoke,OutputStatusSmoke}.java` | Eseguire sul nuovo artefatto, non dedurre PASS dalla presenza |
| Riferimenti | `docs/{keyboard-shortcut-reference,menus-turboj,quick-fix-quick-assist,shortcuts}.md`; quattro PDF `docs/cheatsheets/` | Rigenerare dopo stabilizzazione comandi |
| Esempi | `examples/{Calculator,Hello,SampleTest}.java`; Calculator/CalculatorTest in `examples/maven/` | Distinguere fixture deliberate e lavoro personale; nessun reset indiscriminato |
| Nuovo harness | `app/src/test/java/dev/turboj/app/InlineTemplateAcceptance.java` | Rivedere, eseguire, versionare se pertinente |
| Nuove evidenze | Cinque `benchmarks/results/gui-smoke-2026-10-26T*.md`; `docs/evidence/cherry-native/real-executions.json` | Verificare sorgente/build e conservare artefatti grezzi |
| Nuovo esempio | `examples/HelloWorld.java` | Confermare ruolo e destinazione |

Le modifiche CLI Java, `NativeEvents`, test/fixture, `docs/native-ide-events.md`, provenienza e report Rust sono state consegnate nel sibling Cherry con `302325e8` il 16 novembre. Il riepilogo locale `real-executions.json` rimanda a otto directory `rawEvidence` in `/private/tmp`: al controllo del 14 novembre non sono più presenti. Metadati e hash non sostituiscono gli output grezzi perduti; il file non va pubblicato invariato come prova riproducibile. GOV-01/02 chiuse il 17 novembre: gli otto raw mancanti sono dichiarati non recuperabili e ritirati come prove riproducibili; 93 voci dei manifest successivi e due archivi risultano integri. Le nuove prove CH-01/02 conservano gli artefatti delle proprie sessioni, senza ricostruire quelle perdute. Il censimento corrente preserva 13 file locali preesistenti, classificati e verificati invariati nel [manifest P0](docs/evidence/p0-2026-11-17/preexisting-work.json); la tabella sopra resta una fotografia storica.

## 3. Architettura e capacità disponibili

### Componenti

| Componente | Percorso | Responsabilità |
| --- | --- | --- |
| App | [app](app/) | Swing/Java2D, editor, pannelli, preferenze e controller |
| Core | [core](core/) | Documento, compilazione, intelligence e modello decorazioni |
| Worker | [worker](worker/) | Esecuzione separata, stdin/stdout/stderr e protocollo |
| CLI / benchmark | [cli](cli/), [benchmarks](benchmarks/) | Comandi e misure; non sostituiscono accettazione GUI |
| CommandJ | [commandj](commandj/) | Registro, contesti, profili, palette, conflitti ed export |
| RefactorJ | [refactorj](refactorj/) | JDT/Equinox condiviso, piani/transazioni, adattatori CLI/LSP/MCP/IDE |
| MavenJ | [mavenj](mavenj/) | Effective POM, dipendenze, toolchain, reactor, compilazione/test |
| TurboTest | [turbotest](turbotest/) | JUnit Platform; il controller app possiede anche il processo Cherry |
| TurboAI | AI-13 implementata: trigger selettivi, frequenza limitata e soppressione dopo dismiss | Regressione nativa finale; provider cloud a pagamento separati in AI-05 |
| JavaFMT | [javafmt](javafmt/) | Formatter proprietario e adattatori conservati; sviluppo sospeso |
| Cherry Test | Collezioni indipendenti, rerun esatti supportati, callback JUnit reali e due repository esterni verificati | Inspector e pulsanti sul pacchetto finale; adapter senza callback restano a progressione per fase |
| Website | [website](website/) | Astro statico, replay HTML, temi ed evidenze |

Cherry: **CLI isolata → JSONL v1 e artefatti verificati → modello immutabile → repository/modulo/documento/revisione → corsie e inspector**. `TurboTestController` resta proprietario dell'esecuzione: niente secondo runner o status ricavati dai colori HTML.

### Editor e interazione

- Cut/Copy senza selezione operano sulla riga; una selezione esplicita mantiene la propria semantica. Delete/Duplicate/Move/Copy Lines, commenti, indentazione e altre trasformazioni passano da CommandJ.
- Multicaret reale, undo, selezione AST e movimenti strutturali sono presenti. Trasformazioni insicure vengono rifiutate o richiedono la decisione prevista dal contratto.
- Palette, menu, pannelli, Maven, test, refactoring, temi e preferenze hanno percorsi da tastiera. Le prove automatiche non certificano ogni layout fisico/accessibilità.
- **Quattro profili correnti: TurboJ, IntelliJ, Eclipse, VS Code macOS. Default: TurboJ.** `turboj-classic` è ritirato e mappato al nuovo default. Il requisito storico IntelliJ-default/Classic-preservato va riconciliato; la migrazione custom non va presunta sicura.
- Quick Fix è `refactor.context-actions`. Cmd+1 è presente in TurboJ/Eclipse; IntelliJ e VS Code conservano le proprie convenzioni. Il codice segue anche il problema correggibile più vicino sulla riga.
- Template inline: intervalli reali, rename semantico, Tab/Shift+Tab ciclici, Enter conferma, Escape annulla. Il contratto più recente è [inline-templates](docs/inline-templates.md).
- Bordi singoli, font condiviso e Output ridimensionabile sono implementati; default attuale 16 pt. Screenshot storici a 18 pt non definiscono il default.
- Output è verbatim, Tests separato, JShell distinto. `Escape → : → ! comando` esegue un processo esterno nell'Output; **non è una shell persistente o PTY**.

### Java, build e test

JDT è condiviso da intelligence/RefactorJ, con recovery AST fuori dall'EDT. MavenJ fornisce modello e classpath dei moduli: importare non esegue automaticamente plugin. Compilazione nativa, Fast Run, Isolated Run e goal Maven mantengono semantiche diverse.

TurboTest esegue JUnit realmente. **Auto Test on Save, Auto Run on Save e Auto Test on Changes sono distinti**; i primi due sono indipendenti e disabilitati di default. Compilazione, generazione corrente, esito e focus devono restare verificabili. RefactorJ dispone di journal e operazioni tipizzate, non promette atomicità globale del filesystem o correttezza rispetto a reflection arbitraria.

La generazione Java (constructor/accessor, equals/hashCode/toString, implement/override e Javadoc) è disponibile nei contesti supportati. Non è universale: le operazioni interessate rifiutano records/enums/interfaces, equality con superclasse non `Object` e default-interface ambigui secondo il [contratto keyboard-first](docs/keyboard-first-design.md).

### Cherry: tre fatti indipendenti

| Dimensione | Disponibile | Limite |
| --- | --- | --- |
| Git | HEAD→buffer con staged/unstaged/unsaved, nuovi file, rename noto, worktree e ancore cancellazione | Altre baseline solo API; move non riconosciuto trattato come nuovo file |
| Test | Risultati, runtime/fasi distinti, dichiarazioni risolte dove dimostrabili | Nessuna failure di package attribuita arbitrariamente alla produzione |
| Coverage | Contatori JaCoCo `ci/mi/cb/mb`, LCOV binario, disabled/missing/incomplete distinti; capture opt-in javac/classi/processo | CURRENT disponibile solo sul percorso verificato; altri provider, output riutilizzati e capture assenti restano UNKNOWN |
| UI | Corsie indipendenti, hit-test, tastiera, geometria stabile, palette semantica; inspector della provenienza | Colori correnti solo per evidence valida; nessuna associazione test-produzione inventata |
| Esecuzione | Discovery, processo isolato, cancel, storico con receipt/hash/exit code; rerun singolo/falliti esatto e callback Cherry JUnit verificati | Selettori e pending dipendono dalle capacità dichiarate dell’adapter; altri adapter restano per fase, fallback più ampio solo esplicito |

Nei progetti configurati, Run affianca Cherry all’esecuzione normale senza cambiare le garanzie Fast/Isolated. Cherry valida i file salvati: rifiutare Save non certifica il buffer modificato. La CLI conserva orchestrazione e report standalone.

Coperto non significa test riuscito; non coperto non eredita una failure di package. Codice cancellato è un'annotazione diff, non una riga corrente. Un hash sorgente stabile non dimostra quale bytecode sia stato eseguito. Mostrare meno informazioni è corretto quando mancano queste prove.

### AI, formatter e sito

TurboAI comprende Ollama, llama.cpp, OpenAI Responses, Anthropic Messages e xAI Chat Completions; runtime gestito/esterno, download, errori tipizzati, ghost text, accettazione completa/parola/riga e precedenza JDT/template. AI è disabilitata di default; keep-alive attuale 30 minuti. Contesto current-file e filtri euristici non equivalgono a correttezza provata. Fallback cloud solo con impostazioni e consenso espliciti.

Default formatter: **IntelliJ-style tramite JDT**, non il binario IntelliJ. Disponibili altri preset JDT e google-java-format 1.37.0. JavaFMT/FormatJ resta compatibile con scelte legacy ma sospeso.

Hero: Hello World → due test → Rename/Extract Method → AutoRun/AutoTest → Spring Boot/curl. Il finale aggiornato conserva l'editor e usa `:! curl` nativo registrato, HTTP/JSON autentici; nessuna richiesta browser a localhost. Controlli compatti Play/Pausa, spiegazione typewriter e loop seguono le richieste successive: non ripristinare automaticamente tutti i controlli del primo prompt. Evidenze e dettagli conservati nel progetto.

## 4. Evidenze e documenti superati

### Evidenze storiche, non certificazione di HEAD corrente

| Area | Risultati registrati | Limiti |
| --- | --- | --- |
| Tastiera | [Audit](docs/keyboard-accessibility-audit.md): 577 test/91 suite, controlli profili/pannelli/Robot | Ottobre, prima di cambi successivi |
| First launch | [Accettazione](docs/first-launch-keyboard-acceptance.md): run09, 241 eventi, 100 PASS, plain e Maven | AWT/Swing iniettati, non walkthrough umano |
| Quick Actions | [Rapporto](docs/quick-fix-quick-assist.md): 24 QuickActionsIT + 20 ModernizationIT PASS | Semantica JDT; popup attuale da riprovare |
| Layout/font | [Preview](docs/native-layout-preview-2026-10-25.md): 148+2 layout, 33 zoom; package con 165 test app selezionati | Artefatto storico, non intero reactor ricertificato |
| Cherry/TurboJ | [Validazione](docs/evidence/cherry-native/automated-validation.json): 721 reactor prima degli ultimi fix, poi 690 app/dipendenze inclusi 71 specifici | Esecuzioni sovrapposte: non sommare |
| Cherry standalone | Stessa evidenza: 341 Java, 241 Rust in clone con patch confrontate | Modifiche sibling ancora da consolidare |
| Cherry nativo | [Walkthrough](docs/evidence/cherry-native/native-walkthrough.json): pass→edit→fail→Undo/Save→pass; inspector/corsie | Contatori reali, provenienza compilata sconosciuta |
| Progetti reali | `docs/evidence/cherry-native/real-executions.json` (locale, non versionato): mini Java 3/3, shop cart 12/12, shop-java 32/33 | Failure `reports_a_price_mismatch` conservata, non PASS complessivo |
| JUG | Domain 9/9, Spring 5/5, Quarkus 5/5 nella stessa evidenza | Coverage disabilitata; suite complete non eseguite |
| MavenJ | [VALIDATION](mavenj/docs/VALIDATION.md): reactor e suite mirate di ottobre | Source/API Java 17 non significano runtime Java 17 provato |
| AI | [Validazione](docs/ai/autocomplete-validation.md): Ollama reale, Tab/Undo/JDT; [benchmark](benchmarks/results/ai-completion-2026-10-26.md): 63/84 attesi, 26/26 controlli compilazione | Non 81 suggerimenti tutti compilati; download GUI verificato con fake server, download reali tramite CLI; cloud con fixture di protocollo |
| Sito | [QA](website/QA.md), [hero](docs/website-hero-validation-2026-10-25.md), [deploy](website/DEPLOYMENT.md) | Evidenze ottobre, nessun controllo live oggi |

Le matrici mini/shop/shop-java con servizi/browser/processi separati non sono dimostrate dai sottoinsiemi sopra. JUG richiede i propri launcher e confini di rete locali; configurazione presente non significa autorizzazione ad eseguire suite esterne indiscriminatamente.

### Prestazioni osservate

| Misura storica | Risultato | Confine |
| --- | --- | --- |
| Gutter 100k righe | Paint 290,375→322,541 µs; snapshot 17,497 ms; installazione EDT 0,029 ms | Offscreen, stessa build senza/con decorazioni; non baseline pre-integrazione |
| Git 10k righe | Warm mediana 42,374 ms / p95 46,072 ms | Non input-to-paint né qualsiasi repository |
| Duplicate Line | p95 2,667→5,625 µs; +3.552 B/op | Regressione storica misurata, non «nessuna regressione» |
| AI 1.5B M5/16GB | Warm p50 134 / p95 446 ms; cold 1.034 ms | Non prestazione universale, Intel/7B/cloud non deducibili |
| Managed Ollama | Enable→Ready 1.143 ms; restart configurazione 747 ms | Osservazioni storiche, non SLA |
| Hero | Controller 6.217 byte gzip; JS iniziale 14.360 byte gzip | Budget 8/18 KiB rispettati sulla build misurata |
| Lighthouse locale | 99/100/100/100; LCP 1.807 ms, CLS 0,000697, TBT 0 | Non field INP o misura attuale di produzione |

Fonti: [keyboard-performance](docs/keyboard-performance.md), [Cherry benchmark](docs/evidence/cherry-native/paint-and-git-benchmark.json), [performance](docs/performance.md), [AI performance](docs/ai/autocomplete-performance.md), [QA sito](website/QA.md). La qualificazione del 18 novembre aggiunge input Swing→paint, scrolling/jump nativo, grandi report, sei avvii e un intervallo idle/heap/GC di dieci minuti per artefatto: [misure correnti](docs/evidence/product-sequence-2026-11-18/performance/README.md). Input fisico→presentazione, leak di lunga durata ed effettiva cache OS fredda restano confini distinti.

### Contraddizioni da riconciliare

| Testo storico | Stato da usare | Attività |
| --- | --- | --- |
| IntelliJ default e Classic preservato | TurboJ default, Classic ritirato | KEY-01/02: decisione e migrazione |
| Maven/JUnit assenti in acceptance/sessione23 ottobre | MavenJ/TurboTest implementati | GOV-03 |
| Quick Fix solo sul caret; Escape ripristina sempre posizione | Anche problema più vicino sulla riga; caret resta sul problema | QFX-02 |
| Vecchio ordine template; Tab esce; Escape non rollback | Nuovo ordine return/name/params/expression, ciclo Tab, Enter conferma, Escape annulla | QFX-03 |
| Run genericamente contestuale | Working tree separa `run` e `run.context` | RUN-01 |
| Output con intestazioni/timing/test | Output verbatim; Tests separato; stato altrove | Parte iniziale aggiornata di output-and-status prevale sulla proposta sotto |
| FormatJ attivo o soltanto GJF | Default JDT IntelliJ-style, anche GJF; JavaFMT sospeso | FMT-01 |
| Go to Implementation/progetti/Recent Files assenti | Implementazioni successive nei controller | GOV-03, non riaprire vecchi limiti automaticamente |
| AI senza manager, vecchi provider, keep-alive 2 min | TurboAI/manager, 30 min | AI-08; current-architecture è baseline pre-AI 2.0 |
| Shortcut AI universale | TurboJ: JDT Option+Space, AI Option+Shift+Space; altri profili diversi | KEY-03/AI-08 |
| Hero HelloWorld-only o Calculator/cinque test | Cinque capitoli, due test, due refactoring, automazioni separate | WEB-01 |
| Curl sempre in terminale esterno | Finale nativo `:! curl` successivo | WEB-01; vecchia cattura resta storica |
| Nessuna shell | Nessun PTY persistente, ma comando one-shot presente | RUN-04/06 |
| Cherry Task2 concluso | Slice reale; milestone 4–7 parziali/incomplete | CH-02…16 |

### Confini intenzionali

- Import Maven non esegue wrapper/plugin/AP. Codegen, risorse filtrate, fork custom, Surefire/Failsafe e JUnit named-module con `--patch-module` usano Maven esplicito dove il percorso nativo non li supporta.
- `source/target 8` non prova API/runtime 8; preview precedenti richiedono JDK corretto. Query JDT di un main e lancio effettivo sono prove diverse.
- Fast/Isolated Run non sono sandbox di sicurezza per codice ostile; limiti JVM/processi/output restano dichiarati.
- Unknown, skipped, aborted, failure infrastrutturale, coverage disabled/missing/incomplete e zero istruzioni coperte non sono sinonimi.
- Reflection, sorgenti generati mancanti e binari limitano i refactoring statici; recovery del journal può essere esplicito.
- Gradle, debugger completo, PTY, font arbitrari e indice persistente sono evoluzioni, non capacità già disponibili.
- JavaFMT sospeso non riapre automaticamente lavoro su parsing incrementale, Java malformato/preview, plugin build o GraalVM.
- Telemetria, Electron, framework web pesanti e runner duplicati non sono attività implicitamente richieste.

## 5. Sequenza di lavoro consigliata

Ordine concordato il 16 novembre: **CH-01 → JAVA-06 → CH-02/08/09 → QFX-04**. CH-01 e JAVA-06 consegnate; CH-02/08/09 implementate e qualificate nel perimetro supportato; QFX-04 è ora verificata con il workflow nativo e i controlli dei pannelli inferiori. KEY-04/06 e QFX-03/05 hanno poi passato le verifiche automatizzate; CH-10 è provata sul ciclo JVM. QFX-08 è ora verificata (17 novembre): tipi parametri semantici, compatibilità espressioni e ulteriori template inline. Anche i sei P0 sono ora chiusi nel perimetro dichiarato, con tre difetti Run/Save corretti e build/nativo finale verificati. Il dialogo QFX-10 è stato consegnato; la successiva semantica avanzata è ora implementata e verificata nei test mirati. KEY-05 umano resta rinviato. La sequenza corrente seguente sostituisce le vecchie indicazioni «prossimo AI-13», conservate solo nei rapporti storici, e non autorizza deploy.

**Sequenza di prodotto concordata il 18 novembre**, nell'ordine richiesto. Le priorità originali non cambiano; «implementata» non equivale a qualificazione dell'artefatto finale.

| Ordine | Attività | Stato corrente / prossima prova |
| --- | --- | --- |
| 1 | AI-13 | Trigger, frequenza e dismiss verificati; AIInteractionSmoke finale PASS |
| 2 | QFX-02/06 | Diagnostiche/caret e nuovi provider semantici verificati; QuickFixPopupSmoke finale PASS |
| 3 | QFX-10 semantica | JDT, macro, contesti, import/shorten e format verificati; tre workflow live template e inline semantico finali PASS |
| 4 | UI-07/08, REF-01 | Recovery multi-buffer e conflitti/metadati verificati; RecoverySmoke finale PASS; limiti filesystem REF-01 distinti |
| 5 | CH-03/04/05/07 | Processi reali e inspector/rerun nativi finali PASS; progressione limitata alle capacità dichiarate del producer |
| 6 | REL-02/05/07, GOV-04 | Pacchetto/DMG, avvio LaunchServices, receipt, rapporto e matrice verificati; CI hosted finale macOS Intel/Linux/sito PASS |
| 7 | MVN-01/02/03, FMT-01 | Due prove con runtime Java 17 reale PASS; FormatterSmoke sul pacchetto finale PASS |
| 8 | PERF-01/02/04, CH-16 | Confronti native input/paint/scroll, Git/JDT, JMH, grandi report, startup e idle/heap/RSS completati; outlier e overhead conservati |
| 9 | WEB-02/03/04/06/07 | Browser/budget e build protetta finali PASS; 312 comandi, 29 alias, quattro PDF da quattro pagine senza clipping |

**QUALIFICAZIONE LOCALE E CI COMPLETATA NEL PERIMETRO DICHIARATO:** [rapporto](docs/product-release-2026-11-18.md), [matrice](docs/evidence/product-sequence-2026-11-18/source-artifact-matrix.json) e prove identificano gli ambiti verificati. Il [run finale](https://github.com/turboj-dev/turboj/actions/runs/44378326946) è PASS. Delle 28 voci richieste, 27 sono verificate nel perimetro dichiarato; REF-01 resta parziale per ownership tra utenti e network FS, con rifiuto sicuro delle operazioni distruttive prive di journal completo dei metadati. I limiti di provider e input fisico restano distinti.

| Traguardo | Attività | Uscita verificabile |
| --- | --- | --- |
| A — Baseline affidabile | GOV-01/02, REL-01 | Diff, artefatti e prove identificati senza perdere lavoro |
| B — Nativo stabile | KEY-01/02, QFX-01/03/04, RUN-01/02, UI-01 | Cmd+1/Monokai, template, Run e Save provati |
| C — Cherry completo | CH-01/02/03, poi CH-04…10 | Provenienza, selettori, aggregazione e coverage corrente reali |
| D — Qualificazione | AI, MavenJ, RefactorJ, tastiera fisica e PERF | Runtime/GUI reali e misure comparabili |
| E — Consegna/sito | GOV-03, KEY-03, WEB, REL | Build verificata e hero coerente col nativo accettato |
| F — Ecosistema | ECO-01/02/03 | Naming/compatibilità decisi prima di spostare/pubblicare |

AI, documentazione e test librerie possono procedere in parallelo dove indipendenti. Coordinare harness GUI che contendono focus e processi/porte. Non separare i repository nel mezzo della stabilizzazione Cherry/Run.

## 6. Registro unico delle attività

**P0:** blocca correttezza/consegna nel proprio ambito; **P1:** prossima implementazione/qualificazione; **P2:** miglioramento successivo; **P3:** direzione da decidere. CH-02 è chiusa per il percorso javac/JaCoCo verificato; gli altri provider restano neutrali senza una prova equivalente.

Stati: **IMPLEMENTATA — finale in attesa** (codice e prove mirate presenti, non certificazione finale), **DA FARE**, **PARZIALE**, **INCOMPLETA** (requisito essenziale mancante), **DA VERIFICARE** (codice/evidenza storica presenti, accettazione attuale assente), **DECISIONE**, **SOSPESA**. Chiudere con **CHIUSA + data/commit/evidenza**. Le capacità consegnate sono in §3–4, non replicate come lavoro aperto.

**Registro al 18 novembre: 114 attività aggregate, 66 aperte e 48 chiuse/verificate nel perimetro dichiarato.** Le verifiche a capacità dichiarate conservano i limiti espliciti; decisioni, limiti e qualificazioni non sono difetti dimostrati. I conteggi storici precedenti non qualificano il nuovo artefatto.

| Priorità | Attività aperte | Lettura |
| --- | --- | --- |
| P0 | 0 | Sei voci chiuse con prove correnti; nessun P0 aperto nel registro |
| P1 | 27 | Consolidamento, implementazione e qualificazione prossimi |
| P2 | 31 | Miglioramenti e decisioni successive |
| P3 | 8 | Evoluzioni da decidere o attività sospese |

### 6.1 Governo e documentazione

Fonti: stato Git, [limitations](docs/limitations.md), rapporti di release e precedente TODO nella storia del repository.

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| GOV-01 | P0 | CHIUSA | **17 novembre:** 13 file preesistenti classificati, hash verificati invariati e backup locale separato. Esempi personali, harness legacy e osservazioni storiche preservati ed esclusi dalla consegna; nuove fixture isolate. [Prove P0](docs/evidence/p0-2026-11-17/README.md). |
| GOV-02 | P0 | CHIUSA | **17 novembre:** otto directory raw storiche assenti dichiarate non recuperabili, non ripresentate come prove correnti. Verificate 93 voci di manifest e due archivi successivi; conservati ambiente, SHA, comandi, esiti e controlli falliti della nuova qualificazione. [Prove P0](docs/evidence/p0-2026-11-17/README.md). |
| GOV-03 | P1 | DA FARE | Riconciliare contraddizioni §4, README moduli e copy pertinente. Contratti attuali univoci, rapporti storici datati, nessun backlog parallelo. |
| GOV-04 | P1 | VERIFICATA — matrice pubblicabile | Tutti i 743 input del receipt corrispondono al commit sorgente c7d08485; JAR/pacchetto/runtime, 15 file companion Cherry, 4 PDF e reference collegati con hash. Commit companion locale 9cbd9e1f e patch verificata disponibili senza pubblicare 16 ancestor preesistenti. CI e produzione restano identità distinte. [Matrice](docs/evidence/product-sequence-2026-11-18/source-artifact-matrix.json), [rapporto](docs/product-release-2026-11-18.md). |
| GOV-05 | P1 | DA FARE | Riconciliare requisiti dei prompt con matrice attuale: consegnato, parziale, non verificato, sospeso o sostituito con motivazione; nessuna vecchia specifica riaperta alla cieca. |

### 6.2 CommandJ, keymap e accessibilità

Fonti: [design](docs/keyboard-first-design.md), [audit](docs/keyboard-accessibility-audit.md), [CommandJ](commandj/docs/ARCHITECTURE.md), [profilo TurboJ](docs/keymap-turboj-macos.md), `BundledKeymaps`, `KeymapJson`, `KeymapManager`.

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| KEY-01 | P0 | CHIUSA | 15 novembre: Classic salvato conservato come profilo utente, senza quinto default; selezione, binding personalizzati, unbinding espliciti, contesti/chord e profili figli preservati. Fallback TurboJ solo senza profilo legacy salvato; diagnostica di migrazione e import atomico. Suite keymap PASS, inclusi round-trip/cambio profilo e duplicati respinti; install completo 841 test PASS. Inclusa nella consegna del 16 novembre. |
| KEY-02 | P1 | DECISIONE | Esplicitare TurboJ-default/ritiro Classic rispetto al requisito iniziale. Documentare compatibilità e scelta profilo, senza sovrascrivere preferenze o introdurre silenziosamente un quinto profilo. |
| KEY-03 | P1 | CHIUSA | **17 novembre:** tutti i 309 comandi assegnati nei quattro profili; supplementi stabili Ctrl+Cmd+K + due lettere, nessuna collisione interna, custom/unbinding/alias preservati. Sequenze visibili nei menu; inventario/reference/export sito rigenerati con hash. Quattro PDF da quattro pagine A4 ciascuno, 16 colonne piene, tutti i comandi una volta, nessun testo tagliato. Install 878 test + 42 regressioni finali PASS; smoke nativo 104 asserzioni; [prove e limiti](docs/evidence/keyboard-shortcuts-2026-11-17/README.md). |
| KEY-04 | P1 | VERIFICATA — perimetro automatizzato | 309/309 comandi con effetti verificati e rami annidati identificati completati. Follow-up positivo per AI Local/Cloud/pull, sources/Javadoc, parent POM, processor custom e report browser; remove/reset/import/overwrite keymap. Fixture HTTPS dichiarate, inference locale reale e pull con cache calda: nessuna pretesa di test live dei vendor a pagamento o di ogni input/condizione remota. [Prove e riproduzione](docs/evidence/key04-effects-2026-11-17/boundary-followup/README.md). KEY-05 fisico/VoiceOver resta rinviato. |
| KEY-05 | P1 | PARZIALE — prove umane RINVIATE | Su richiesta dell’utente: rinviati tastiere fisiche US/IT/CH, Fn/Globe, candidate window IME e ascolto/navigazione VoiceOver. Eseguiti probe read-only di cinque tabelle Apple (765 traduzioni, 25 continuazioni dead key), regressioni IME Unicode e lettura sorgente/selezione tramite il vero bridge macOS AX. Solo US abilitato; Swiss French non certifica Swiss Italian. [Limiti](docs/evidence/keyboard-completion-2026-11-17/README.md). |
| KEY-06 | P1 | VERIFICATA — perimetro automatizzato | Corretti leak del suffisso chord nei dialoghi e bypass menu durante preedit IME. 10.168 casi binding/contesto; AI Ollama reale nei quattro profili: Tab/Undo, Escape, precedenza JDT/template e dialoghi. Custom/persistenza e workflow nativi regressi. Conflitti host e input fisico rinviati in KEY-05; altre configurazioni provider non certificate. [Prove](docs/evidence/keyboard-completion-2026-11-17/README.md). |
| KEY-07 | P2 | PARZIALE | Corretto accesso al sorgente: EditorComponent implementa Accessible, AccessibleText/EditableText, notifiche e geometria; lettura sorgente e selezione verificata tramite macOS AX. Restano follow-up del walkthrough VoiceOver/KEY-05 e casi UI non esercitati; non dedurre accessibilità completa dal registro o dal solo AXValue. |

**Verifiche residue dopo KEY-04 (rinviate, non incluse nella chiusura automatizzata):**

- [ ] **KEY-05 — tastiere fisiche:** walkthrough dell’utente sui layout US, italiano, svizzero italiano e svizzero tedesco disponibili, nei quattro keymap. Verificare Cmd/Option/Control/Shift, Fn/Globe/F1–F12, dead key e conflitti con shortcut macOS; registrare layout, hardware e risultati effettivi.
- [ ] **KEY-05 — IME reale:** composizione Unicode e finestra candidati; conferma/annullamento, navigazione tra candidati e assenza di comandi globali durante preedit. Gli eventi sintetici e le tabelle Apple già verificate non sostituiscono questo controllo.
- [ ] **KEY-05 — VoiceOver:** ascoltare realmente annunci, sorgente, selezione e posizione del cursore; navigare editor, pannelli e dialoghi con ritorno del focus. Le letture AX automatizzate non certificano la resa vocale.
- [ ] **AI-05 — inference cloud live:** con credenziali e consenso dell’utente per eventuali costi, verificare OpenAI, Anthropic e xAI su snippet sintetici, annotando modello/provider, risposta, accettazione e cancellazione senza conservare chiavi o sorgenti personali. I protocolli HTTPS, Keychain e rami di errore hanno già passato fixture isolate; nessuna inference live a pagamento è stata eseguita né viene dichiarata verificata.

### 6.3 Quick Fix, Eclipse e template

Fonti: [Quick Fix](docs/quick-fix-quick-assist.md), [parità](docs/eclipse-workflow-parity.md), [accettazione](docs/first-launch-keyboard-acceptance.md), [template](docs/inline-templates.md), harness `QuickFixPopupSmoke`, `QuickFixPaletteTest`, `InlineTemplateAcceptance`.

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| QFX-01 | P0 | CHIUSA | **17 novembre:** Calculator reale con Cmd+1 nei profili TurboJ/Eclipse, caret sul tipo e dopo `;`; frecce/Return generano la classe e ripristinano focus. Contrasto normale/selezione/lista vuota >=4.5:1, Classic/Monokai e cambio tema verificati. Fixture canoniche: resta il guard RefactorJ sui workspace con alias simbolico. [Prove P0](docs/evidence/p0-2026-11-17/README.md). |
| QFX-02 | P1 | VERIFICATA — perimetro automatizzato | Intersezioni diagnostiche semiaperte, ordinamento deterministico sulla riga e seconda invocazione durante la richiesta semantica senza riavvio. Test multipli/caret/CRLF e QuickFixPopupSmoke sul pacchetto finale PASS: burst, frecce, Enter/Escape, focus e contrasto. Controlli iniziali falliti conservati; nessuna certificazione tastiera fisica. [Prove semantiche](docs/evidence/quick-fix-precision-2026-11-18/README.md), [nativo finale](docs/evidence/product-sequence-2026-11-18/native-final/results.tsv). |
| QFX-03 | P1 | VERIFICATA — perimetro automatizzato | 17 novembre: template generati, caller rename cross-file, campi invalidi, completion JDT con placeholder conservati, Save/Save All/Format, cambio file, Escape e Undo/Redo per transazione. Corretti validazione nomi/tipi e precedenza Enter nella completion. IME fisico resta KEY-05; tipi parametri e compatibilità espressioni verificati successivamente in QFX-08. [Prove](docs/evidence/live-templates-2026-11-17/README.md). |
| QFX-04 | P1 | CHIUSA | **16 novembre:** plain e Maven da workspace a JUnit verde, Monokai/Eclipse, 470 eventi tastiera e 172 asserzioni; Save/Run/Palette/focus da Output, Tests, JShell e command line, modali isolati. Corretti ciclo Tests, accesso Tests vuoto, tasti/focus JShell e perdita dell’espressione durante rename linked. Quattro profili/custom persistiti: 92 asserzioni; install 874 test PASS. [Evidenze e limiti](docs/evidence/qfx04-2026-11-16/README.md). Eventi Swing reali, non certificazione di layout fisici/IME/VoiceOver. |
| QFX-05 | P2 | VERIFICATA | `System.out.print` → proposte JDT → documentazione della proposta selezionata con owner/signature reali → Escape → `println()` accettato. Corretto resolve della documentazione quando la chiamata non è ancora valida; test distingue overload e invalida binding obsoleti. [Prove](docs/evidence/live-templates-2026-11-17/README.md). |
| QFX-06 | P2 | VERIFICATA — provider supportati | Eccezioni con binding reali: throws o catch applicabili, identità/posizione diagnostica validata anche su caller/override; interrupt conservato. Costruttori e anonymous-to-lambda contestuali; contesti insicuri rifiutati. 49 QuickActionsIT, 22 ModernizationIT e 10 protocol test PASS; reactor e popup nativo finale PASS. Non parità Eclipse completa. [Prove](docs/evidence/quick-fix-precision-2026-11-18/README.md). |
| QFX-07 | P2 | DECISIONE | Parità wizard oltre minimo: Save-and-Launch per-file/remember, superclass/interfaces/module-info/working set, framework/versioni JUnit/lifecycle stub e archetype Maven. Confermare necessità prima di aggiungere UI. |
| QFX-08 | P2 | VERIFICATA — perimetro automatizzato | 17 novembre: tipi parametri inline via Change Signature, aggiornamento gerarchia sorgente, verifica caller e rifiuto di overload retargeting; validazione JDT asincrona delle espressioni e dei campi prima di Enter/Save/Format. Extract Variable/Method/Create Field mantengono preview e aprono campi inline con rename semantico, Escape e Undo/Redo per transazione. PASS: 41 test QuickActionsIT, 330 test app e sei suite native, inclusi first-launch plain/Maven e precedenza AI/template. Limiti: gerarchie ambigue/binarie rifiutate, nessuna correzione automatica degli argomenti; verifica umana KEY-05 separata. [Prove e riproduzione](docs/evidence/qfx08-2026-11-17/README.md). |
| QFX-09 | P2 | DECISIONE | Dependency editing JUnit in POM non UTF-8: preservare encoding e dipendenze; finché non supportato mantenere rifiuto esplicito e percorso alternativo. |
| QFX-10 | P2 | VERIFICATA — semantica supportata | Scope e tipi JDT reali, macro annidate, contesti Consumer/IO/preview vincolati alle API, import/shorten sicuri e format asincrono. Save differito, risposte obsolete rifiutate, un solo Undo/Redo; macro ambigue editabili. Test mirati/reactor e LiveTemplateWorkflow/Precedence/DialogSmoke più InlineSemanticTemplateSmoke finali PASS. Macro script arbitrarie non eseguite. [Semantica e limiti](docs/evidence/template-semantics-2026-11-18/README.md), [nativo finale](docs/evidence/product-sequence-2026-11-18/native-final/results.tsv). |

**QFX-10 — dialogo e semantica avanzata verificati il 18 novembre nel perimetro supportato, inclusi i workflow nativi finali:**

- [x] Usare l'editor nativo `EditorComponent` in modalità preview read-only per mostrare l'esempio del template, con syntax highlighting, tema e font/zoom coerenti con l'editor principale.
- [x] Aggiungere un campo di ricerca rapido per filtrare il catalogo per nome, abbreviazione e descrizione, con selezione da tastiera e aggiornamento della preview.
- [x] Introdurre un form per aggiungere e modificare **nome**, **abbreviazione**, **esempio/corpo del codice** e **configurazione** del template: contesti applicabili, variabili/placeholder, valori predefiniti, espressioni e abilitazione.
- [x] Mostrare nella preview l'espansione risultante, i placeholder e la posizione finale del cursore (`$END$`), distinguendo la definizione dall'esempio espanso.
- [x] Validare i campi prima del salvataggio, preservare definizioni personalizzate e override dei built-in; rendere ricerca, creazione, modifica, conferma e annullamento interamente accessibili da tastiera. Il JSON resta il formato persistente, senza richiederne la modifica manuale per le operazioni comuni.

### 6.4 Editor, layout, temi ed Explorer

Fonti: [layout](docs/native-layout-preview-2026-10-25.md), [Explorer](docs/project-explorer.md), [temi](docs/themes.md), [limiti](docs/limitations.md), [keyboard design](docs/keyboard-first-design.md).

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| UI-01 | P1 | DA VERIFICARE | Artefatto attuale: bordi singoli, Output resize, font uniforme, zoom 10–48/HiDPI, tab nella cornice, finestre piccole, console input/focus. Registrare accettazione nativa prima di nuovi allineamenti hero. |
| UI-02 | P1 | DA VERIFICARE | Clipboard/undo/caret/selection su prima/ultima riga, EOF senza newline, CRLF/Unicode, multicaret sovrapposti e AST move. Commenti preservati e scope/semantica insicuri rifiutati. |
| UI-03 | P1 | DA VERIFICARE | Explorer via Shift+F10/tastiera: dirty buffer, file generici/binari, Trash, Java rename/move/delete/undo. Operazioni non journaled mantengono conferme e limiti espliciti. |
| UI-04 | P2 | DA FARE | Eventuali move classe/package main↔test/source-root con refactoring, classpath e undo coerenti; oggi non esposti. |
| UI-05 | P2 | PARZIALE | Modalità rettangolare persistente con frecce: oggi esiste conversione in selezioni reali, non modalità persistente completa. |
| UI-06 | P2 | PARZIALE | Grapheme cluster completi, bidi e accessibilità renderer. Correttezza UTF-16/surrogati non equivale a ogni scrittura complessa. |
| UI-07 | P2 | VERIFICATA — recovery supportato | Recovery schema-v2 di tutti i buffer editabili, identità/caret/scroll/encoding, deadline non rinviata dalla digitazione, isolamento multiistanza, handoff atomico e crash JVM reale. Corruzione/legacy conservati senza bloccare altri recuperi. Limiti 256 buffer/128 MiB espliciti; niente overwrite o trust automatico. RecoverySmoke sul pacchetto finale PASS. [Prove](docs/evidence/recovery-2026-11-18/README.md). |
| UI-08 | P2 | VERIFICATA — confine di sicurezza dichiarato | Rilettura finale di contenuto, target simbolico e metadati dopo staging; conflitti ACL/xattr/permessi reali preservano file esterno e buffer. Staging macOS conserva ACL/xattr. Test filesystem, reactor e recovery sul pacchetto finale PASS. Nessuna promessa compare-and-swap globale contro writer non cooperanti. [Prove](docs/evidence/product-sequence-2026-11-18/native-metadata/README.md). |
| UI-09 | P1 | DA VERIFICARE | Palette su 103 temi/custom: popup, selezione, focus, disabled e corsie. Cache aggiornata al cambio; contrasto e forme semantiche distinti. |
| UI-10 | P3 | DECISIONE | Famiglia font personalizzabile/fedeltà retro: loader assente; zoom uniforme non equivale a scelta arbitraria del font. |

### 6.5 Intelligence Java, refactoring e formatter

Fonti: [Java](docs/java-intelligence.md), [limiti](docs/limitations.md), [RefactorJ](refactorj/docs/FEATURES.md), [transazioni](refactorj/docs/TRANSACTIONS.md), [formatter](docs/formatting.md).

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| JAVA-01 | P1 | DA VERIFICARE | Navigazione/ricerca simboli e tipi, definizioni/implementazioni/riferimenti, gerarchie supportate, errori/warning/history/source↔test su plain/Maven/unsaved. Non dedurre copertura dal nome del comando. |
| JAVA-02 | P2 | DA VERIFICARE | Lancio reale main ereditati/annidati/Java 25: distinguere query JDT, scelta target e backend. Documentare main multipli per plain/Maven e completare eventuale chooser mancante. |
| JAVA-03 | P3 | DECISIONE | Indice persistente, package completion e parsing realmente incrementale: recovery AST completo off-EDT e cache non equivalgono a queste capacità. |
| JAVA-04 | P2 | PARZIALE | Javadoc/inheritDoc, sorgenti dipendenze e link/documentazione; distinguere quick documentation sicura da renderer HTML completo dei JAR. |
| JAVA-05 | P1 | DA VERIFICARE | Qualificare source generation, Surround With e Complete Statement: contesti validi, duplicati/overload, membri final/static, compilazione del risultato e undo; rifiuti dei casi non supportati espliciti. |
| JAVA-06 | P1 | CHIUSA | **16 novembre, implementata e verificata; inclusa nella consegna JAVA-06.** Find Usages con chiamante/file/riga, filtro, preview e Enter/Escape; alias e binding conservati. Default ClassTest/Class diretto solo con package/modulo coerenti; chooser esplicito con usi statici verificati dentro test JUnit risolti e navigazione inversa alle dichiarazioni usate. Overload, contratti ereditati, moduli, unsaved, Unicode/CRLF e risposte obsolete verificati. Nessuna attribuzione dinamica Cherry inferita. Otto test JDT, reactor 866 test/124 suite e acceptance nativa plain/Maven PASS. Reflection/binari/classpath irrisolti possono lasciare risultati incompleti; nessuna analisi transitiva via helper. [Contratto](docs/find-usages.md), [evidenze](docs/evidence/find-usages-2026-11-16/README.md). |
| REF-01 | P1 | PARZIALE — limiti espliciti | Transazioni locali, crash/restart, directory concorrenti e metadati same-path verificati: 40 controlli finali isolati PASS. Case-only alias su FS insensibile e delete/rename con ACL/xattr non ripristinabili rifiutati prima di scrivere. Restano non verificati ownership tra utenti e network FS; journal distruttivo completo dei metadati non supportato. Nessuna atomicità globale. [Prove](docs/evidence/recovery-2026-11-18/README.md), [metadati](docs/evidence/product-sequence-2026-11-18/native-metadata/README.md). |
| REF-02 | P2 | DA VERIFICARE | IntelliJ/VS Code/LSP/MCP su versioni host dichiarate, undo e resource operations. ZIP/VSIX/protocol test non equivalgono a compatibilità host universale. |
| REF-03 | P2 | PARZIALE | Ridurre allocazioni semantiche non cached; misurare worker/cancel/code. Cache-hit da soli non descrivono il costo reale RefactorJ. |
| REF-04 | P0 | CHIUSA | 14 novembre: eliminata la dipendenza di QuickActionsIT dal checkout JUG mutabile che rendeva obsoleta l’asserzione `50013`. Fixture versionate autosufficienti; conservazione esatta del codice estraneo, compilazione e undo/redo. Suite QuickActionsIT: 34 test PASS, zero skip; install completo PASS con 834 test. Correzione inclusa nella consegna del 14 novembre; evidenza in §7. |
| FMT-01 | P1 | VERIFICATA — pacchetto locale | Default JDT IntelliJ-style, preset e GJF con sei export javac nel launcher jpackage. FormatterSmoke PASS sul JAR e runtime bundled finali: layout, selezione preset, Undo/Redo, preferenze e Format on Save; controlli documento/selezione/CRLF e cancellazione nel reactor. Manifest JAR da solo non sostituisce i flag -cp. [Nativo finale](docs/evidence/product-sequence-2026-11-18/native-final/FormatterSmoke.txt). |
| FMT-02 | P2 | DECISIONE | Configurazione formatter per progetto anziché solo globale, precedenza esplicita e nessun cambio sorgente silenzioso. |
| FMT-03 | P3 | SOSPESA | Ripresa e rename JavaFMT→FormatJ solo dopo ECO-01; preservare CLI `javafmt`, consumatori, package/coordinate e build. Nessuna riattivazione automatica. |

### 6.6 MavenJ, esecuzione e TurboTest

Fonti: [MavenJ](mavenj/docs/VALIDATION.md), [compatibilità](mavenj/docs/EFFECTIVE_POM.md), [test Maven](mavenj/docs/TEST_INTEGRATION.md), [TurboTest](turbotest/README.md), [Output](docs/output-and-status.md), [comandi Output](docs/output-command-line.md).

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| MVN-01 | P1 | VERIFICATA — runtime reale | 18 novembre: compiler/editor Java25, toolchain dichiarata Corretto17, main e worker JUnit eseguiti realmente su17; Maven compiler/Surefire17 verificati nel log. Due test di qualificazione PASS senza skip; non solo --release. [Prove e riproduzione](docs/evidence/maven-toolchain-2026-11-18/README.md). |
| MVN-02 | P1 | VERIFICATA — perimetro dichiarato | Parent e tre moduli reali, profilo, dependencyManagement, risorse, toolchain, buffer unsaved e generazioni dipendenti, cache modulo indipendente e pruning verificati. BOM/module-path/AP coperti da test reactor esistenti. Misure first/warm del modello con dipendenze già cached; nessun benchmark download a cache fredda. [Prove](docs/evidence/maven-toolchain-2026-11-18/README.md). |
| MVN-03 | P2 | VERIFICATA — lifecycle esplicito | Import/native compile non eseguono generatori/plugin. Maven17 verify esplicito con antrun/build-helper genera sorgente, filtra risorse, esegue Surefire e Failsafe reali. AP trust/refusal e named-module worker restano controlli separati del reactor; nessuna emulazione universale plugin o JUnit module-path. [Prove](docs/evidence/maven-toolchain-2026-11-18/README.md). |
| MVN-04 | P3 | DECISIONE | Scope Maven model 4.1/Gradle e fixture prima di nuovi importatori; non dichiararli compatibili oggi. |
| MVN-05 | P2 | PARZIALE | Valutare invalidazione ABI intra-modulo dopo misure, preservando dipendenze/generazioni corrette. |
| RUN-01 | P0 | CHIUSA | **17 novembre:** target precedente vincolato alla root plain/Maven; main assente/multipli, classe attiva, Run/context/JUnit e isolamento verificati. Stop ora annulla anche compilazione/preparazione/context resolution; edit invalida il lancio tardivo. Workflow tastiera plain/Maven PASS sul JAR finale. [Prove P0](docs/evidence/p0-2026-11-17/README.md). |
| RUN-02 | P0 | CHIUSA | **17 novembre:** 78 asserzioni Save plain/Maven su fixture congelate: toggle separati/insieme, Stop indipendenti, coalescing/stale, compile failure, nessun trigger da typing, trust e focus. Corretta reimportazione ridondante aprendo POM invariato; modifica POM reale ancora importata. [Prove P0](docs/evidence/p0-2026-11-17/README.md). |
| RUN-03 | P1 | DA VERIFICARE | Scanner/stdin/EOF/stdout/stderr, Stop/timeout/System.exit, restart worker e argomenti; status IDE separato da output e processi non posseduti intatti. |
| RUN-04 | P1 | DA VERIFICARE | `Escape → : → !command`: cwd/history, quoting shell, output bound, stdin EOF, cancel/timeout. Processo reale senza promessa PTY/persistenza. |
| RUN-05 | P2 | PARZIALE | Preferenze GUI heap/output/timeout oggi API/configurazione parziale; validazione e compatibilità mantenute. |
| RUN-06 | P3 | DECISIONE | Debugger JDI, JLine/PTY e CLI residente come evoluzioni separate; niente duplicato del mini comando o terminale fittizio nella demo. |
| TEST-01 | P1 | DA VERIFICARE | Progetti senza Cherry: discovery, metodo/classe/progetto/falliti, stack/dichiarazioni, cancel, suite parziali e Tests UI. Compile failure distinto da assertion failure. |
| TEST-02 | P1 | DA VERIFICARE | Auto Test on Changes vs Save, priorità falliti/durate reali, skip per generazione modulo; comportamento e limiti espliciti. |
| TEST-03 | P2 | PARZIALE | Test impattati solo con dipendenze/attribuzione dimostrate; coordinare CH-05/06. In assenza di prova dichiarare/eseguire scope più ampio. |

### 6.7 Cherry Test e tre dimensioni

Fonti: [architettura](docs/architecture/cherry-test-turboj-integration.md), [fixture](integration-tests/fixtures/cherry-native/README.md), [evidenze](docs/evidence/cherry-native/), contratto sibling `../cherry-test/docs/native-ide-events.md`.

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| CH-01 | P0 | CHIUSA | 16 novembre: Cherry `302325e8` consegnato su `lmrrcc/cherry-test`, TurboJ `1414347` su `turboj-dev/turboj`. Checkout scaricati da GitHub, cache senza artefatti TurboJ/formatter preinstallati e Cherry ricostruito da sorgenti pubblicati: install TurboJ 858 test, reactor CLI Cherry 308, Rust 241, smoke nativo pass→Save/fail→Undo/Save/pass PASS. Corretti due presupposti dei test Maven (repository fisso e directory report assente). [Riproduzione e prove](docs/evidence/cherry-native/delivery-2026-11-16/README.md). Consegna sorgenti compatibili, non pubblicazione Maven Central/binari; la release 0.3.16 va identificata anche con lo SHA. GOV resta distinto; la successiva chiusura CH-02/08/09 è documentata in §7. |
| CH-02 | P0 | CHIUSA — javac/JaCoCo | Pipeline completa per output fresh: input compiler, SHA classi, ID/probe `.exec`, PID/sessioni e receipt verificati. Adapter server e union provider dei probe ora implementati. Save mini: 36 test, 81 righe/5 file con proof; shop: 425 righe correnti da 3 JVM. Edit/bytecode/missing/cancel invalidano; unloaded resta unknown salvo esecuzione provata nella union. Altri compiler e output incrementali/reused senza capture sono esplicitamente UNSUPPORTED, mai correnti per inferenza. [Consegna](docs/evidence/cherry-native/completion-2026-11-16/README.md). |
| CH-03 | P1 | VERIFICATA — collezioni indipendenti | Osservazioni per collezione/provider/fase/runtime/artifact consultabili in inspector e risultati, con limiti di excerpt dichiarati. Union a probe, mai somma dei contatori. Due collezioni precise correnti su repository esterni reali e inspector CherryNativeSmoke finale PASS. [Prove](docs/evidence/cherry-native/feedback-2026-11-18/README.md), [follow-up nativo](docs/evidence/product-sequence-2026-11-18/native-final-followup/results.tsv). |
| CH-04 | P1 | VERIFICATA — selettori supportati | Selettori tipizzati con manifest/fase/runtime/adapter, SHA richiesta riconosciuto e inventario finale confrontato. Rerun selezionato/fallito reali e pulsanti tastiera CherryNativeSmoke finale PASS. Casi custom/parameterized/dynamic/ordered non selezionabili spiegati con fallback suite esplicito; Rust exact testato nel producer, non nuova esecuzione Rust. [Prove](docs/evidence/cherry-native/feedback-2026-11-18/README.md). |
| CH-05 | P1 | VERIFICATA — capacità dichiarate | Run/Save eseguono tutte le categorie configurate con coalescing/cancel verificati; SaveAutomationSmoke finale PASS. Eventi reali pending/start/completion Cherry JUnit e storico limitato a 12 esecuzioni indipendenti verificati; nessun vecchio PASS fuso nel nuovo run. Plain JUnit/altri adapter restano per fase senza callback: inventario totale ed eventi universali NON SUPPORTATI, mai simulati. [Prove](docs/evidence/cherry-native/feedback-2026-11-18/README.md). |
| CH-06 | P1 | PARZIALE | Contratti ereditati con classpath/modulo; distinguere dichiarazione, stack/assertion, target esplicito e relazione dinamica. Euristiche restano candidati, non prova. |
| CH-07 | P1 | VERIFICATA — identità supportate | Due worktree esterni con HEAD, package e FQN identici verificati separatamente tramite repository/modulo/runtime/fase. Sorgenti/classi esterne solo nelle root esplicite live o storia con receipt; import generici confinati. Full/exact/failure/restored, provenienza e integrazione nativa finale PASS. JUG Spring/Quarkus conserva evidenze precedenti distinte. [Prove](docs/evidence/cherry-native/feedback-2026-11-18/README.md). |
| CH-08 | P1 | CHIUSA — qualificazione | Mini Java: Save nativo 3 unit + 25 integration + 4 functional + 4 visual PASS, coverage corrente nell’editor. Shop Java: 12 integration PASS, 3 JVM provate; 51/52 unit con failure preesistente preservata. Kotlin Spring: 25 integration PASS, contatori precisi ma provenance unknown. Rust shop: 12 integration PASS, LLVM/LCOV separato neutrale. Non equivale a certificare tutti i provider o tutte le suite opzionali. [Prove](docs/evidence/cherry-native/completion-2026-11-16/README.md). |
| CH-09 | P1 | CHIUSA — nativa | Protocollo nativo unificato con sidecars; TurboJ rispetta configured/local/container per progetto. Run JUG dall’app: 33 fasi, 1701 pass/1 skip, Spring/Quarkus/browser distinti, coverage DISABLED. Controllo config coverage=true: 16 JUnit PASS, contatori helper UNKNOWN e backend non strumentato non colorato; config ripristinata esattamente. Riconciliati conteggi HTML: 1702 esecuzioni -25 nomi collassati +43 gruppi HTTP =1720 righe. Cancel Docker reale PASS. Save routing container verificato da regressione; Save nativo completo dimostrato su mini. [Prove](docs/evidence/cherry-native/completion-2026-11-16/README.md). |
| CH-10 | P1 | VERIFICATA — flusso JVM | 17 novembre: `Price.deliveryFee` realmente UNCOVERED (0/2 istruzioni) → ispezione → `PriceTest` convenzionale → nuovo metodo test scritto da tastiera → solo Save → Cherry automatico 3 PASS → COVERED (2/0), con source/class/process proof corrente. Nessuna attribuzione per-test inventata. Fixture Maven/JUnit/JaCoCo reale, suite unit configurata; non certifica ulteriori provider o generazione automatica del corpo test. [Prove](docs/evidence/live-templates-2026-11-17/README.md). |
| CH-11 | P2 | PARZIALE | Preflight config/CLI/protocollo ed errori precoci; Rust/legacy senza eventi non compatibili implicitamente. Decidere eventuale contratto nativo Rust. |
| CH-12 | P2 | DECISIONE | UI baseline Git alternative già accettate dall'API; submodule/rename non staged qualificati senza inventare identità. HEAD resta default. |
| CH-13 | P2 | PARZIALE | Fast-first con categorie/dipendenze/risorse/durate reali Cherry e copertura eventuale suite; nessun secondo scheduler indipendente o tempo inventato; concorrenza sicura delle suite ammessa. |
| CH-14 | P1 | DA VERIFICARE | Stale/current, switch documento/progetto, cancel, receipt/hash, eventi tardivi e close context; corsie stabili fino alla rimozione del contesto. |
| CH-15 | P2 | DECISIONE | Eventuale import legacy oltre JSONL storico: unknown/incomplete dove mancano prove, mai status da HTML/ANSI. |
| CH-16 | P1 | VERIFICATA — confronto pre-batch | Confronto controllato baseline dd75866/current: tre JVM per build, 10k/100k test e righe. 100k: 411,214→435,694 ms mediana, heap trattenuto 55,25→66,77 MiB; costo esplicito delle osservazioni, non speedup. Baseline pre-batch già include Cherry, non pre-integrazione. Input/paint/scroll nativi separati; workload protocollo sintetico dichiarato. [Misure](docs/evidence/cherry-native/feedback-2026-11-18/README.md). |

Task2 non si chiude per la sola presenza dei decorator. CH-02/08/09 e CH-10 mantengono le proprie qualificazioni; CH-03/04/07 sono provate con processi reali e controlli UI finali. CH-05 è verificata nelle capacità dichiarate, con inventario/callback universali non supportati; CH-16 distingue misure pre-batch da una baseline pre-integrazione. Gli altri ambiti aperti restano distinti.

### 6.8 TurboAI

Fonti: [TurboAI](turbo-ai/README.md), [manager](docs/ai/local-model-manager.md), [validazione](docs/ai/autocomplete-validation.md), [performance](docs/ai/autocomplete-performance.md), [troubleshooting](docs/ai/autocomplete-troubleshooting.md).

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| AI-01 | P1 | DA VERIFICARE | `.app` corrente da Finder/PATH minimo: discovery/enable/restart/disable/quit/kill/watchdog, niente orfani, server esterni intatti. Prove packaged AI 1.0.5 non certificano AI 2.0. |
| AI-02 | P1 | DA VERIFICARE | Download reale GUI: consenso/spazio/progresso/cancel/errore/disponibilità. Distinguere fake-server smoke e download autentici CLI già eseguiti. |
| AI-03 | P1 | DA VERIFICARE | GUI Test Completion reale e MODEL_NOT_FOUND/endpoint/namespace, spiegazioni leggibili e preferenze persistenti. |
| AI-04 | P1 | DA VERIFICARE | Managed llama.cpp con runtime/GGUF veri: start/load/completion/cancel/ownership; compilazione adattatore non basta. |
| AI-05 | P1 | DA VERIFICARE — inference live | OpenAI/Anthropic/xAI live richiedono credenziali e consenso per eventuali costi. Usare snippet sintetici, verificare risposta/accettazione/cancellazione e registrare modello/provider senza segreti nei log. Il 17 novembre Apply/Keychain/HTTPS/stream/errori dei tre provider sono verificati con fixture isolate; questo non certifica inference dei vendor reali. [Prove automatiche](docs/evidence/key04-effects-2026-11-17/boundary-followup/README.md). Checklist residua in §6.2. |
| AI-06 | P2 | DA VERIFICARE | Intel/sleep-wake/restart/crash ripetuti/fallback, retry limitati e nessun cloud implicito. |
| AI-07 | P2 | DECISIONE | Eventuale `llama-server` bundled: licenze/dimensioni/hardened runtime/notarizzazione. Oggi binario runtime installato separatamente, gestibile da TurboJ; embedding JNI/FFM non è un impegno. |
| AI-08 | P1 | PARZIALE | Docs provider/credenziali/keep-alive/build/shortcut per profilo aggiornati; baseline pre-AI 2.0 storica, contesto e consenso descritti correttamente. |
| AI-09 | P1 | DA VERIFICARE | Precedenza JDT/template/AI, Tab/parola/riga/type-through, UTF-16, stream obsoleto, undo/debounce e falsi rifiuti corpus, senza blocco editor. |
| AI-10 | P2 | DA VERIFICARE | Benchmark 7B se risorse disponibili, CPU/GPU/memoria/cold-warm/typing latency. Separare attesi, mostrati e realmente compilati. |
| AI-11 | P3 | DECISIONE | Contesto inter-file/Maven/retrieval/ranking con budget e consenso; oggi current-file e filtri, non comprensione completa repository. |
| AI-12 | P3 | DECISIONE | Eventuale pubblicazione TurboAI dopo ECO-01/03 con API/versioni/licenze/consumer; modulo locale non equivale a Maven Central. |
| AI-13 | P2 | VERIFICATA — interazione automatizzata | Trigger automatici solo su inserimenti pertinenti, limite di frequenza, dismiss della stessa riga senza retry autonomo e streaming automatico dopo validazione. 20 test controller e sei asserzioni Swing PASS; AIInteractionSmoke e precedenza template/JDT sul pacchetto finale PASS. Invocazione esplicita preservata. Nessuna misura soggettiva o paid-cloud implicita. [Prove](docs/evidence/product-sequence-2026-11-18/ai13/README.md). |

### 6.9 Website e demo

Fonti: [QA](website/QA.md), [deployment](website/DEPLOYMENT.md), [hero](docs/website-hero-release-2026-10-25.md), [finale](docs/website-native-console-finale.md), [capture](website/demo/capture/), [tests](website/tests/).

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| WEB-01 | P1 | PARZIALE | Docs coerenti con cinque capitoli, Play/Pausa, mini prompt, loop e curl nativo. Non ripristinare vecchio terminale esterno né eliminare evidenze nascoste. |
| WEB-02 | P1 | VERIFICATA — ricostruzione locale | Hero con singola dimensione font per breakpoint, bordi singoli e resize Output/Explorer da pointer/tastiera, riferita al chrome nativo verificato. Sorgenti, output, comandi, tempi e tagli delle catture immutati; ricostruzione dichiarata. Browser ed export/build protetta finali PASS. Produzione invariata. [Prove](docs/evidence/website-visibility-2026-11-18/README.md). |
| WEB-03 | P1 | VERIFICATA — replay locale | Timeline/evidenze, seek rapido, pause-hidden-offscreen, loop, temi e assenza Classic flash PASS. Corrette race close/reopen del tema e lettura del primo evento di visibilità in batch: usa ora l’ultimo. Sei controlli fallivano prima e passano dopo, incluso Play a 320 px; build protetta e intera suite 375 PASS, nessun risultato anticipato. [Prove](docs/evidence/website-visibility-2026-11-18/README.md). |
| WEB-04 | P1 | VERIFICATA — accessibilità automatizzata | 320/390/768/1440/1920, 103 temi applicati da tastiera, focus/contrasto/reduced-motion/no-JS, sorgenti/log e resize: 375 controlli browser (sei nuovi su batch di visibilità) più controllo storico del dialogo PASS, build finale PASS. CTA/logo preservati; nessuna certificazione WCAG completa o VoiceOver fisico. [Prove](docs/evidence/website-visibility-2026-11-18/README.md). |
| WEB-05 | P2 | DA VERIFICARE | Safari/Firefox/screen reader/audit umano; Chromium/axe storici non certificano ogni browser o accessibilità. |
| WEB-06 | P1 | VERIFICATA — budget locale | Budget controller≤8192 B gzip, JS≤18432 e capitoli lazy≤30720 verificati anche dalla build/test finale. Misure renderer: 6727/14874/21080 B rispettivamente; niente archive/video eager. Lighthouse mobile locale tre volte100 in quattro categorie, LCP circa1657 ms, TBT0. Non latenza hosting o miglioramento storico controllato. [Misure](docs/evidence/website-visibility-2026-11-18/README.md). |
| WEB-07 | P1 | VERIFICATA — export finale | KeyboardRegistryAudit finale: 312 comandi e 29 alias, manifest/hash/reference aggiornati. Quattro PDF, ciascuno quattro pagine e 16 colonne, tutti i 312 comandi senza clipping. npm run build protetta e 53 test PASS senza bypass; reference derivate dal registro. Nessun deploy. |
| WEB-08 | P1 | DA VERIFICARE | Differenze locale/commit/deploy e destinazione verificate; nuova pubblicazione solo nel relativo incarico autorizzato. Questo consolidamento non pubblica. |
| WEB-09 | P2 | DECISIONE | Nuova cattura eventuale per cache Maven fredda/stop Spring graceful. Demo attuale warm; exit 137 precedente prova terminazione, non shutdown graceful. Video originale facoltativo. |
| WEB-10 | P2 | DECISIONE | Scegliere se raccontare AI/Cherry/formatter aggiornati; soltanto capacità verificate, descrivere coverage corrente soltanto nel perimetro CH-02 verificato. |

### 6.10 Prestazioni, distribuzione e qualità

Fonti: [performance](docs/performance.md), [tastiera](docs/keyboard-performance.md), [intelligence](docs/java-intelligence-performance.md), [dipendenze](docs/dependencies.md), [licenze](docs/licenses.md), [script](scripts/).

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| PERF-01 | P1 | VERIFICATA — confronto dichiarato | Baseline dd75866 e JAR finale sul medesimo Corretto25/hardware/dataset: 18 JVM per input/paint, salti, dense gutter/Git/JDT; 36 fork JMH per dispatch/shortcut/palette. Distribuzioni, p95/p99 e outlier conservati. CommandJ è identico nei due artefatti: misura di ripetibilità, nessuna promessa speedup. [Misure](docs/evidence/product-sequence-2026-11-18/performance/README.md). |
| PERF-02 | P1 | VERIFICATA — eventi Swing e paint | Confronto baseline/candidato sullo stesso runtime: 120/120 eventi dipinti per caso, file 1k/10k e 100 salti distanti plain/dense; paint offscreen 100k separato. Outlier candidato 34,873 ms input→paint conservato. Non input fisico o presentazione compositor. [Misure e raw](docs/evidence/product-sequence-2026-11-18/performance/README.md). |
| PERF-03 | P2 | PARZIALE | Piece list/frammentazione/addition buffer, paste e righe lunghe sull'EDT; mantenere correttezza e limiti 32 MiB/undo 2000 finché contratto non cambia. |
| PERF-04 | P1 | VERIFICATA — pacchetto e intervallo dichiarato | Sei avvii LaunchServices con JVM/profili nuovi: mediana baseline 771,118 ms, candidato 846,696 ms; cache OS calda. Venti campioni/10 minuti per artefatto per CPU/RSS app+worker; heap e GC letti in finestre distinte, non sincronizzate con tutti i dieci minuti, nessun forced GC. Controlli iniziali conservati. Non misura energia, leak prolungati, AI carica o cache disco fredda. [Raw e confini](docs/evidence/product-sequence-2026-11-18/performance/README.md). |
| PERF-05 | P2 | DA VERIFICARE | QuickFix first/warm, RefactorJ non cached, parsing/dipendenze/grandi workspace/report/Git; separare libreria/worker/EDT. |
| PERF-06 | P2 | PARZIALE | Analizzare Duplicate Line e allocazioni storiche, accettare o correggere con confronto riproducibile senza cambiare semantica per il benchmark. |
| REL-01 | P0 | CHIUSA | **17 novembre:** `./mvnw clean install` PASS, 907 test/132 suite/39 moduli; 15 controlli nativi PASS sul medesimo JAR SHA-256 `bdb6e727…12ba2`. Dataset conserva SHA completo, sorgenti, tempi, esiti e tentativi falliti. Qualificazione JAR locale; packaging/signing separati REL-02. [Prove P0](docs/evidence/p0-2026-11-17/README.md). |
| REL-02 | P1 | VERIFICATA — pacchetto locale | App copiata dal DMG e verificata: 123 file, 99 symlink interni e permessi coerenti con receipt schema 2; codesign strict PASS. Runtime bundled esegue 13 controlli nativi finali incluso KeyboardProfileSmoke; sei avvii LaunchServices con profili isolati PASS. App locale ad-hoc, non Developer ID/notarizzata. [Consegna e identità](docs/product-release-2026-11-18.md). |
| REL-03 | P2 | DA VERIFICARE | Intel/architetture/OS dichiarati: eseguire o restringere matrice; nessuna estrapolazione Apple Silicon. |
| REL-04 | P2 | DECISIONE | Signing/notarizzazione/updater: canale/processo riproducibile prima di promesse; nessun aggiornamento automatico implicito. |
| REL-05 | P1 | VERIFICATA — hosted macOS Intel/Linux | [Run finale 44378326946](https://github.com/turboj-dev/turboj/actions/runs/44378326946) PASS su `48815c8`: reactor, toolchain Corretto17 reale, entrypoint standalone, receipt, app/DMG ad-hoc e upload limitato ai moduli. Linux include QuickFixPopupSmoke/TestOutputSmoke con Xvfb; sito: 53 test unitari e 375 controlli browser. Skip e artefatti distinti per piattaforma; fallimenti precedenti conservati. Non sostituisce input fisico, qualificazione hosted ARM o notarizzazione. [Prove](docs/evidence/product-sequence-2026-11-18/ci/README.md). |
| REL-06 | P1 | DA VERIFICARE | Deps runtime/notices/licenze temi-font-modelli/engine e manifest per artefatto pubblicabile aggiornati. |
| REL-07 | P1 | VERIFICATA — rapporto e identità | [Rapporto di consegna](docs/product-release-2026-11-18.md) e [matrice](docs/evidence/product-sequence-2026-11-18/source-artifact-matrix.json) collegano input, JAR, runtime, pacchetto/DMG, patch companion, reference e prove reali. Ricevute storiche e controlli falliti conservati. Label bundle 1.0.10 storica esplicita; nessun deploy o release notarizzata impliciti. |

### 6.11 Naming ed estrazione ecosistema

Le tre attività del precedente TODO sono preservate; TurboAI si aggiunge alla valutazione della famiglia, senza rename implicito.

| Oggi | Famiglia `*J` proposta | Famiglia Turbo proposta |
| --- | --- | --- |
| `javafmt` | FormatJ | TurboFormat |
| `refactorj` | RefactorJ | TurboRefactor |
| `commandj` | CommandJ | TurboCommand |
| `mavenj` | MavenJ | TurboMaven |
| `turbotest` / `turbo-ai` | Decisione esplicita | TurboTest / TurboAI già esistenti |

| ID | P | Stato | Attività e criterio di chiusura |
| --- | --- | --- | --- |
| ECO-01 | P2 | DECISIONE | Naming prima di rename/spostamenti: matrice package/coordinate/CLI/repository/docs/sito e compatibilità consumer. |
| ECO-02 | P2 | DA FARE | Sibling indipendenti dopo stabilizzazione: TurboJ, formatter, RefactorJ, CommandJ, MavenJ, TurboTest, valutazione TurboAI. Decidere se sospendere anche l’estrazione del formatter: il precedente TODO sospendeva motore/rename, ma includeva la sua estrazione. Storia/build/test/release preservati, CI/packaging aggiornati, librerie versionate in-process; dipende da ECO-01/CH-01. |
| ECO-03 | P3 | DECISIONE | Pubblicazioni eventuali: namespace/credenziali/firma/canali/versioni/test consumer. ZIP/VSIX/JAR locali non sono pubblicazioni; protocolli esterni opzionali. |

Il rename `javafmt`→`formatj` è **FMT-03, SOSPESA**, subordinato alla famiglia scelta; preserva CLI `javafmt` e verifica consumatori prima di package/coordinate. Nessuna delle tre attività precedenti è stata eliminata o duplicata in due stati concorrenti.

## 7. Verifica e definizione di completamento

### Sequenza di prodotto corrente — 18 novembre 2026

**QUALIFICAZIONE LOCALE E CI COMPLETATA NEL PERIMETRO DICHIARATO:** avvii LaunchServices, misure comparabili e matrice/rapporto finale disponibili; [CI hosted finale PASS](https://github.com/turboj-dev/turboj/actions/runs/44378326946) su macOS Intel/Linux/sito. Il reactor finale ha eseguito **976 test / 141 suite, zero failure/error/skip**. Il collector originale includeva due risultati preesistenti di un esempio fuori reactor: il riepilogo corretto li esclude e conserva l’inventario originale per audit. Pacchetto/DMG e receipt schema 2 verificati: 123 file confrontati, codesign strict PASS. I controlli seguenti usano il JAR e runtime bundled finali, senza certificare hardware/IME o servizi non eseguiti.

- [Nativo finale](docs/evidence/product-sequence-2026-11-18/native-final/results.tsv): AIInteractionSmoke, QuickFixPopupSmoke, LiveTemplateWorkflowSmoke, LiveTemplatePrecedenceSmoke, LiveTemplateDialogSmoke, InlineSemanticTemplateSmoke, RecoverySmoke, FormatterSmoke, KeyboardProfileSmoke e RunTargetIsolationSmoke PASS.
- [Follow-up finale](docs/evidence/product-sequence-2026-11-18/native-final-followup/results.tsv): CherryNativeSmoke e SaveAutomationSmoke PASS dopo correzioni del driver; tentativi falliti conservati, non riscritti. TestOutputSmoke PASS sullo stesso runtime bundled: quattro esiti JUnit reali, output e temi verificati.
- Export finale: 312 comandi, 29 alias; quattro PDF, ciascuno quattro pagine/16 colonne/312 comandi senza clipping. Build sito protetta e 53 test PASS; nessun deploy.

- [AI-13](docs/evidence/product-sequence-2026-11-18/ai13/README.md): 20 test controller e sei asserzioni Swing; [QFX-02/06](docs/evidence/quick-fix-precision-2026-11-18/README.md): 81 test semantici/protocollo PASS.
- [Template semantici](docs/evidence/template-semantics-2026-11-18/README.md): 33 test integrati iniziali e 23 controlli headless successivi; non sommare le riesecuzioni come test unici. [Recovery](docs/evidence/recovery-2026-11-18/README.md) e [metadati](docs/evidence/product-sequence-2026-11-18/native-metadata/README.md): 40 controlli finali isolati PASS, con fallimenti originari conservati.
- [Cherry consumer/processi reali](docs/evidence/cherry-native/feedback-2026-11-18/README.md): due repository esterni, rerun esatti e callback pending reali. [Producer](docs/evidence/cherry-producer-feedback-2026-11-18/README.md): **425 voci, 423 test eseguiti PASS, due controlli Docker opt-in saltati**, nessun failure/error. JAR `01004385b5163b3ef9aad1b55f01b482956640aeee79e8fe7f29e6c72a937f25`; sibling inizialmente pulito, ahead16 preesistente preservato, nessun push.
- [Maven/runtime Java17](docs/evidence/maven-toolchain-2026-11-18/README.md): due test PASS senza skip, main e JUnit su JVM17 reale, generatori/risorse/Surefire/Failsafe solo tramite lifecycle esplicito.
- [Sito finale](docs/evidence/website-visibility-2026-11-18/README.md): 53 test unitari, 375 controlli browser e controllo storico della race dialogo; sei nuove regressioni visibilità, budget e tre nuovi Lighthouse locali PASS. Export finale e build normale protetta da manifest del registro PASS. Nessun deploy.
- [.github/workflows/verify.yml](.github/workflows/verify.yml): [CI finale PASS](https://github.com/turboj-dev/turboj/actions/runs/44378326946) su `48815c8`, macOS Intel/Linux/sito, JDK17 Corretto distinto dal build JDK25, entrypoint standalone, app/DMG/receipt e upload completati; effetti Swing Linux/Xvfb verificati. Conservati i controlli falliti di isolamento settings, glob artefatti, acquisizione runner ARM, visibilità browser e aspettativa Intel. [Risultati e skip per piattaforma](docs/evidence/product-sequence-2026-11-18/ci/README.md); nessuna certificazione tastiera fisica.
- File personali e prove storiche preservati secondo il manifest GOV-01; nessun reset dei repository o riscrittura dei 16 commit Cherry preesistenti. KEY-05 fisico e AI-05 paid-cloud restano separati.


### QFX-10 — consegna storica del dialogo, 18 novembre 2026 (prima della sequenza corrente)

- Form nativo con ricerca per nome/abbreviazione/descrizione/ID/codice; New/Duplicate, override/disabilitazione e Restore default. Campi strutturati, contesti e tabella variabili; JSON avanzato opzionale e riparazione dei cataloghi invalidi senza sovrascrittura implicita.
- Preview `EditorComponent` read-only con espansione reale, campi linked e caret finale disegnati; contesto di esempio dichiarato, nessuna esecuzione o promessa di inferenza semantica del progetto. Tema/font/tab condivisi; descrizione accessibile specifica.
- Save atomico fuori EDT, draft conservati cambiando filtro/selezione; Cancel non scrive. Metadati `name`/`description` opzionali schema v1, proprietà JSON sconosciute e definizioni non modificate preservate.
- **PASS:** `./mvnw -pl app install`, 338 test; cinque suite native (dialogo, workflow template, precedenza AI, inline e Find Usages). Controllo aggiuntivo con key injection rapida PASS. Il primo controllo aveva osservato una newline inattesa nel sorgente; non riprodotta dai controlli successivi con tracciamento delle modifiche, causa non confermata e tentativo conservato.
- JAR SHA-256 `8e28b64e98bae20f94c10c5bdf9c47d0c9c1f3f55f41711dcf456049a3530f55`; [rapporto, screenshot e limiti](docs/evidence/template-dialog-2026-11-18/README.md). Sorgenti e prove inclusi nel commit che aggiorna questo registro; nessun installer o sito pubblicato. Alla data di questa consegna del dialogo, QFX-10 era parziale e il successivo intervento era AI-13. È una fotografia storica: la sequenza corrente ha poi implementato AI-13 e la semantica avanzata, con qualificazione finale indicata sopra. KEY-05 fisico resta separato.

### P0 — correttezza e build finale, 17 novembre 2026

- **RUN-01/02, QFX-01, REL-01, GOV-01/02 chiuse** nel perimetro dichiarato. Corretti riuso del main tra progetti, Stop durante preparazione Run e reimportazione POM su semplice navigazione che invalidava Save.
- **Build:** `./mvnw clean install` PASS, **907 test / 132 suite / 39 moduli**, zero failure/error/skip, 3 min 57 sec con cache dipendenze calda.
- **Nativo:** **15 controlli PASS** sullo stesso artefatto; Save automation 78 asserzioni; workflow tastiera plain/Maven 176 asserzioni e 494 eventi. Quick Fix crea Calculator in TurboJ/Eclipse e verifica leggibilità Classic/Monokai. Controlli eseguiti in sequenza; driver riattiva solo la propria finestra quando macOS la disattiva, senza assegnare focus a un componente per forzare il risultato.
- **Artefatto:** `app/target/turboj-app.jar`, SHA-256 `bdb6e72728a30024a59931651f67b4775d6ff514c67ddfec27c32d44fee12ba2`. Baseline `a712d51138855ec0b9ba25dc9e134414ffc536d8` più le correzioni identificate nel commit che contiene queste prove. L’ultimo adeguamento del driver standalone first-launch è compilato separatamente, senza cambiare il JAR.
- **Governance:** 13 file personali/storici invariati e preservati; otto raw storici mancanti non recuperati né inventati. Verificati separatamente 93 hash di evidenze successive e due archivi leggibili.
- [Rapporto, riproduzione e limiti](docs/evidence/p0-2026-11-17/README.md), [dataset degli esiti accettati](docs/evidence/p0-2026-11-17/validation.json). Tentativi falliti conservati, non convertiti retroattivamente in PASS. Nessuna nuova misura prestazionale, pubblicazione o sostituzione della `.app` installata. KEY-05 fisico e AI-05 cloud a pagamento restano separati.

### Controlli svolti per questo consolidamento

Ispezionati stato Git, cronologia, documenti e punti di codice rilevanti; effettuata revisione separata dei perimetri nativo, AI/Cherry e sito/ecosistema. Nel consolidamento iniziale: controllati i 110 ID allora presenti, riferimenti, collegamenti locali e censimento dei 150 documenti di supporto; `git diff --check` senza errori. Quell’incarico ha modificato soltanto `TODO.md`, preservando i cambi preesistenti. Questi controlli documentali non sono build o test dell'applicazione.

### Verifica KEY-01/CH-02 — 15 novembre 2026

- **KEY-01 chiusa:** individuata e corretta la perdita dei profili Classic salvati. Sono profili utente conservati, non un quinto default: binding, rimozioni esplicite, chord/contesti, selezione e figli ereditati sopravvivono al round-trip. Import duplicati respinto senza modificare lo stato; diagnostica della migrazione disponibile.
- **CH-02 parziale ma nativa:** nuovo modulo Cherry `java/cherry-test-provenance`, plugin javac e agente di processo opt-in. Output inizialmente vuoto, hash dell'input/compiler/classi, ID JaCoCo, probe e contatori ricalcolati, PID/sessioni, receipt di shutdown ed effettiva uscita dei processi. TurboJ verifica identità/receipt e conserva neutralità per risultati pending/incomplete, mismatch e provider senza capture. La coverage aggregata può essere corrente anche con un test fallito; nessuna attribuzione rossa inventata alla produzione.
- **TurboJ finale:** `./mvnw install` → BUILD SUCCESS, **841 test in 122 suite**, zero failure/error/skip, 39 progetti reactor. Durata Maven 3:04. Le verifiche mirate del contratto/lifecycle e dei keymap sono incluse nel totale; non sommarle.
- **Cherry reale:** dalla root TurboJ, `./mvnw -q -f ../cherry-test/java/pom.xml -pl cherry-test-cli -am install` → PASS, **308 test in 47 suite** dei moduli selezionati common/core/provenance/CLI, inclusi **8 test** che lanciano javac e JVM JaCoCo reali. Non è una verifica del reactor Rust o dei moduli Java esclusi.
- **Nativo finale:** `CherryNativeSmoke` con il JAR reale, Maven nel PATH e fixture isolata → PASS. Due test verdi, modifica salvata con un solo fallimento, Undo/Save/rerun con due verdi. Provenienza CURRENT nell'editor, inspector via tastiera, Escape/focus e tre hit region indipendenti verificati. Sono eventi Swing reali con setup/caret programmatici, non una prova di tastiera fisica/layout macOS.
- **Controlli falliti conservati:** primo controllo CLI senza `mvn` nel PATH; primo smoke nativo con cache Maven ereditata dalla root TurboJ. La fixture ora ha una propria `.mvn/maven.config`, quindi non eredita accidentalmente la configurazione del repository contenitore. Nessuno dei due controlli è contato come PASS.
- **Evidenze:** [dataset e hash](docs/evidence/cherry-native/provenance-2026-11-15/validation.json), [report nativo](docs/evidence/cherry-native/provenance-2026-11-15/native-report.txt), [inspector](docs/evidence/cherry-native/provenance-2026-11-15/coverage-inspection.png), [editor](docs/evidence/cherry-native/provenance-2026-11-15/editor.png). I tre run conservano source/class/session identity e contatori effettivi; i riferimenti ai dati grezzi in `app/target` sono locali e riproducibili con la fixture, non archiviati qui.
- **Artefatto TurboJ finale:** `app/target/turboj-app.jar`, SHA-256 `6b05143e2b2a77e2d1cff107489851cd5aa02617e1b174d162d6129b4ca962d8`.
- **Limiti aperti:** hook espliciti e class output fresco obbligatori; ECJ/Kotlin/Rust, build incrementali, classi prive di record di esecuzione e qualificazione mini/shop/shop-java/JUG non certificati. Nessuna attribuzione dinamica per-test. Non ci sono nuovi claim prestazionali. I repository mantengono le modifiche locali preesistenti; queste modifiche non sono ancora committate/pubblicate.

### Verifica successiva — Cherry completo al salvataggio, 15 novembre 2026

- **Comportamento:** `Run All Tests` conserva TurboTest e accoda anche Cherry se configurato. Ogni Save riuscito che cambia il fingerprint su disco accoda tutte le fasi configurate attraverso la CLI senza selettori; nessun toggle Auto Test on Save richiesto per Cherry. La configurazione e gli adapter restano responsabili delle fasi e delle dipendenze; non si inventano test mancanti e non si abilita la mutation separatamente opt-in.
- **Lifecycle:** debounce 350 ms, una sola esecuzione Cherry attiva e un seguito coalescente; attesa compilazione/test nativi e import Maven. Cancel/Stop Tests, cambio progetto e chiusura rimuovono il seguito. I risultati precedenti a un Save restano stale anche se arriva una risposta tardiva. Nessuna esecuzione per Save invariato, write fallita o progetto senza configurazione; gate Maven trust preservato.
- **Regressioni mirate:** `./mvnw -q -pl app -am package -Dtest=CherrySaveWorkflowTest,LineIntelligenceControllerTest,CherryTestProcessLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS, 24 test. Sette nuovi controlli per coalescing, seguito durante run, attesa compilazione, cancellazione, trust/config assente, cambio progetto e chiusura. Le quattro fasi sono controllate nel trasporto con processi sintetici.
- **Nativo reale:** `CherryNativeSmoke` → PASS. Run All Tests: 2/2 JUnit; modifica + solo Save: 1/2; Undo + solo Save: 2/2 e coverage corrente nell'editor. Save invariato non avvia un quarto run. [Report](docs/evidence/cherry-native/save-workflow-2026-11-15/native-report.txt), [run e hash JUnit](docs/evidence/cherry-native/save-workflow-2026-11-15/validation.json). Non sono state rieseguite le suite browser/service dei progetti mini/shop/JUG.
- **Controllo ambientale:** primo test Swing nel sandbox terminato con JVM exit 134; lo stesso comando fuori sandbox passa. Non è contato come test superato.
- **Build finale aggiornato:** `./mvnw install` → BUILD SUCCESS, **848 test in 123 suite**, zero failure/error/skip; 3:18. Include i 24 controlli mirati, da non sommare. SHA-256 `app/target/turboj-app.jar`: `922270b9d7893ce518eb826f2814c34655ef493dc469ff7bbbc4bb0a7ddd770b`. Questo artefatto sostituisce quello della verifica CH-02 precedente.

### QFX-04 — workflow completo e shortcut nei pannelli, 16 novembre 2026

- **VERIFICATO:** una sessione nativa da profilo pulito crea progetti plain e Maven via tastiera, genera HelloWorld/Calculator/test/metodo, distingue assertion failure da compile failure, applica linked rename con Undo/Redo e raggiunge JUnit verde. **470 eventi, 172 asserzioni**, nessun recupero foreground nella sessione finale.
- **CORRETTO:** il ciclo dei pannelli riconosce Tests e torna alla tab selezionata; Focus/Toggle Tests funzionano anche prima del primo test. JShell non consuma tasti modificati, Shift+Tab attraversa i controlli e la fine di un’operazione non ruba focus. Un rename semantico non ripristina più `0` sopra un’espressione modificata.
- **VERIFICATO:** Save, Run, Palette, Escape e Ctrl+F6/Ctrl+Shift+F6 da quattro controlli inferiori, in entrambi i progetti. Settings modali bloccano Save/Run/Palette; dopo Escape i comandi tornano disponibili. Quattro profili e override persistiti: **92 asserzioni**. Clipboard: 11 controlli; output command line: 25 controlli, con prova mouse separata esplicitamente etichettata.
- `./mvnw install`: **874 test / 126 suite, zero failure/error/skip**. Popup Cmd+1 TurboJ/Eclipse e contrasto Classic/Monokai ≥4,5:1 PASS. Registro rigenerato: 308 comandi, 29 alias, nessun binding privo di callback; sei test Node della reference PASS.
- [Rapporto, log, screenshot, sorgenti, controlli falliti conservati e hash](docs/evidence/qfx04-2026-11-16/README.md). Test Swing automatizzati; layout fisici, IME/dead keys, Fn/Globe, VoiceOver e walkthrough umano restano KEY-05. Nessun deploy o nuovo pacchetto macOS.

### Verifica successiva — coverage visiva nativa, 16 novembre 2026

- **Implementato:** tre corsie Git/Test/Coverage conservate; righe con coverage corrente azzurre o gialle; partial con simbolo `◧`, senza sottolineatura dopo la revisione visiva. Nessun colore di test derivato dalla coverage. Unknown/stale restano neutrali; selezione ed enfasi si disegnano sopra la tinta. Le righe AI contenenti proposte rimangono neutre; le successive righe sorgente mantengono il mapping.
- **Simboli:** font dell'editor, Unicode `✓ × ■ □ ◧` e fallback ASCII `v x C U P` quando il glifo manca o eccede la cella; `+` Git, notch di cancellazione al confine, `?` unknown e `~` stale. Legenda testuale nell'inspector Coverage.
- **Ritocco successivo:** eliminato il punto segnaposto nella corsia Tests quando esiste coverage ma nessun test associato. Geometria e inspector restano disponibili; i veri stati dei test conservano i simboli. Package del launcher ricostruito con `./mvnw -q -pl cli -am package -Dtest=LineIntelligenceEditorTest,LineIntelligenceThemeTest -Dsurefire.failIfNoSpecifiedTests=false -Djava.awt.headless=true`: PASS, 10 controlli rendering/temi, inclusa corsia vuota verificata a 1×/2×. Questo ritocco è successivo agli screenshot e all'hash del build completo sotto riportati.
- **Scelta finale confermata:** tinte discrete e simboli, nessuna sottolineatura coverage. Rimossa anche l'ipotesi di sottolineatura limitata al testo; partial resta distinguibile da `◧`. Ripetuto lo stesso package mirato: PASS, 10 controlli, con verifica raster dell'assenza di underline a 1×/2×. Preview attuale in `app/target/coverage-quiet-preview/`; gli screenshot archiviati del primo intervento restano storici.
- **Opzione e palette successive:** View → Show Coverage Backgrounds (`view.coverageBackgrounds`, CommandJ) nasconde tutti gli sfondi mantenendo simboli, geometria ed evidenze; preferenza persistente `showCoverageBackgrounds`, default true. Palette azzurro/ciano e giallo/ambra distinte per tutti i 103 temi, derivate da hue semantico e luminanza del tema. Inchiostri sintattici corretti e cached solo sulle righe colorate: contrasto almeno 4.5:1 dove già raggiunto, altrimenti non inferiore al tema originale. Override opzionali `coverage.coveredBackground`/`coverage.uncoveredBackground`.
- **Verifica opzione/palette:** package CLI con `-Dtest=LineIntelligenceEditorTest,LineIntelligenceThemeTest,CommandSettingsTest,MenuLayoutTest -Dsurefire.failIfNoSpecifiedTests=false -Djava.awt.headless=true` → PASS, 16 test; persistenza, menu, toggle, tutti i temi e selezione. `CherryNativeSmoke` → PASS con toggle CommandJ su coverage reale e pass→fail→pass. Non rieseguito l'intero reactor per questo ritocco. [Manifest e hash](docs/evidence/cherry-native/coverage-theme-options-2026-11-16/validation.json), [103 palette risolte](docs/evidence/cherry-native/coverage-theme-options-2026-11-16/theme-palettes.json), [galleria pagina 1 di 5](docs/evidence/cherry-native/coverage-theme-options-2026-11-16/themes-1.png). Tutte le cinque pagine ispezionate; i campioni sono sintetici e dichiarati.
- **Layout diviso sinistra/destra:** Git e Test rimangono prima dei numeri di riga; Coverage si sposta al bordo destro del viewport, con gli stessi simboli e semantica. Testo, selezione e tinte non invadono la cella; hit-test e tooltip seguono resize/scroll. Package CLI e 17 test mirati PASS (103 temi, zoom, HiDPI, resize, selezione); CherryNativeSmoke reale PASS. [Evidenze e preview](docs/evidence/cherry-native/split-gutters-2026-11-16/validation.json). Nessun nuovo collegamento test/riga inferito.
- **Delimitatori coverage:** righe composte solo da `{`/`}` (ignorando whitespace/commenti) senza sfondo né simbolo coverage. Contatori raw preservati e spiegazione in inspector; Git/Test indipendenti, statement e text block invariati. `javap` conferma il return implicito sulla graffa finale di main. Package CLI + 19 test mirati PASS; due smoke nativi interrotti da `Focus lost` (primo arrivato pass→fail→pass), non dichiarati PASS completi. [Evidenze](docs/evidence/cherry-native/brace-coverage-2026-11-16/validation.json).
- **Source↔Test e neutralità coverage:** counterpart univoco `Class.java`/`ClassTest.java` aperto direttamente; per ambiguità resta il chooser. Corretto il focus iniziale JOptionPane che finiva su `x`: Enter ora conferma la selezione, Escape annulla. Rimossa la dot `NOT_APPLICABLE` a destra; inspector e indicatori unknown/stale invariati. Package CLI + 29 test mirati PASS; `SourceTestNavigationAcceptance` nativo PASS per entrambe le direzioni e chooser Enter/Escape. [Evidenze](docs/evidence/source-test-navigation-2026-11-16/validation.json). Find Usages/test alternativi semantici pianificati in JAVA-06, non implementati qui.
- **Placeholder sui test:** oltre alla dot NOT_APPLICABLE, nascosto `?` sulla dichiarazione di un metodo test identificata da associazione semantica esatta quando non esiste coverage misurata. Restano test pass/fail a sinistra, inspector con motivo, unknown sul codice di produzione, stale e misure autentiche sui test. Regressione distingue dichiarazione vera, relazione alla riga errata e coverage misurata.
- **Temi (prima iterazione, superata dalla palette successiva):** tinta derivata dai ruoli semantici, cache per tema, preservazione contrasto dei token. In questa prima iterazione la tinta poteva rimanere neutra nei temi con contrasto insufficiente; la palette successiva sopra descritta risolve sfondi distinti per tutti i temi. Nessuna modifica ai JSON dei 103 temi o al CSS del report Cherry.
- **Verifica mirata:** `./mvnw -q -pl app -am package -Dtest=LineIntelligenceEditorTest,LineIntelligenceThemeTest -Dsurefire.failIfNoSpecifiedTests=false -Djava.awt.headless=true` → PASS, 10 test; 103 temi, zoom campionati 10/24/48, scale raster 1×/2×, selezione, stale, indipendenza test/coverage e AI. Preview Monokai/light/fallback ASCII ispezionate visivamente; sono fixture sintetiche dichiarate.
- **Nativo reale:** `CherryNativeSmoke` → PASS con JUnit/JaCoCo: pass→Save/fail→Undo/Save/pass, coverage corrente, tre hit region, inspector e focus. [Report](docs/evidence/cherry-native/coverage-visual-2026-11-16/native-report.txt), [manifest](docs/evidence/cherry-native/coverage-visual-2026-11-16/validation.json), [editor reale](docs/evidence/cherry-native/coverage-visual-2026-11-16/native-editor.png), [preview Monokai sintetica](docs/evidence/cherry-native/coverage-visual-2026-11-16/vscode-monokai.png).
- **Build precedente ai successivi ritocchi:** `./mvnw install` → BUILD SUCCESS, **850 test in 123 suite**, zero failure/error/skip, 3:48. Include i 10 controlli rendering, da non sommare. SHA-256 `app/target/turboj-app.jar`: `b6a16cf116f986f87a37c438c7e38033a8a47adce3fae61728e33a618adad949`. Launcher locale `./tj` aggiornato; pacchetti `.app` già distribuiti non ricostruiti da questo comando.

### Consegna consolidata — 16 novembre 2026

- `./mvnw install` completo → **BUILD SUCCESS**, **858 test in 123 suite**, zero failure/error/skip; 03:45 min. Include la soppressione finale di `?` sulle dichiarazioni test prive di misure. [Manifest finale](docs/evidence/source-test-navigation-2026-11-16/final-install.json).
- Il primo tentativo in sandbox è terminato con abort del JVM Swing; il rerun completo fuori sandbox è passato. Il walkthrough nativo source↔test/Enter/Escape resta documentato separatamente; non è un test fisico di tutti i layout tastiera.
- La prima consegna `be9723cb` era limitata a TurboJ. Esempi manuali e materiali locali non pertinenti preservati fuori dal commit. La successiva consegna Cherry `302325e8` è registrata separatamente sotto CH-01.

### CH-01 — consegna Cherry e riproduzione pulita, 16 novembre 2026

- Cherry `302325e8`: revisione e push del protocollo opzionale JSONL, contatori JaCoCo completi, catena compiler/classi/processi e separazione failure/coverage nei report Java/Rust. Checkout sibling pulito dopo il commit.
- TurboJ `b90d5db` e `1414347`: i checkout puliti hanno rivelato due difetti degli harness Maven, prima nascosti dalla cache e dalla directory `target/` locali. Repository configurato rispettato e directory dei report creata esplicitamente. Nessun cambiamento del comportamento applicativo in questi fix.
- Build finali da sorgenti scaricati da GitHub: TurboJ `clean install` **858 test / 123 suite PASS**, Cherry CLI e dipendenze `clean install` **308 test / 47 suite PASS**, Rust CLI/core `--locked` **241 PASS**. Zero failure/error/skip nelle suite Java finali. Cache terze parti calda; artefatti progetto esclusi, poi costruiti dai commit pubblicati.
- `CherryNativeSmoke` sui nuovi JAR: **PASS**, 26 asserzioni; coverage corrente con prova, nessuna associazione produzione inventata, invalidazione unsaved, Save con un fallimento, Undo/Save con due test verdi, tre regioni gutter e focus. Nessuna certificazione dei layout tastiera fisici.
- [Report e comandi riproducibili](docs/evidence/cherry-native/delivery-2026-11-16/README.md), [manifest](docs/evidence/cherry-native/delivery-2026-11-16/validation.json), [artefatti grezzi](docs/evidence/cherry-native/delivery-2026-11-16/native-evidence.tar.gz). Conservati anche entrambi i build falliti; le vecchie acquisizioni senza output grezzo restano storiche, non ricertificate.
- Nessuna pubblicazione sito, pacchetto macOS o Maven Central. Prossimo sviluppo **JAVA-06**, poi qualificazione **CH-02/08/09**, infine **QFX-04** come concordato.

### CH-02/08/09 — implementazione dopo la qualificazione, 16 novembre 2026

- **Implementata**, non soltanto documentata: server manifest, compiler/process capture mini/shop, union reale dei probe fra generazioni compatibili, import terminale e invalidazione. 81 righe/5 file mini provate dopo Save, 425 righe shop da tre JVM.
- **JUG nativo PASS:** 33 fasi/1702 esecuzioni distinte. Config coverage disabilitata e controllo abilitato verificati separatamente; originale ripristinato. Nessuna relazione test→produzione dedotta dal colore HTML.
- **Esecuzione:** nuova azione CommandJ `cherry.execution`, preferenze per progetto, Save usa la stessa policy; TERM concede cleanup sidecars prima del kill. Cancel reale lascia zero container del run.
- **Build:** `./mvnw install` TurboJ PASS, 873 test/125 suite, 0 failure/error/skip (esclusi report storici examples); Cherry Java 393 test con due smoke Docker inizialmente skipped e poi eseguiti PASS; Rust 317 + due Docker opt-in PASS; shop adapter 7 PASS. Registro generato: 308 comandi/29 alias/zero binding senza callback.
- **Companion locali:** Cherry `39af874d`, mini `9811e78`, shop-java `b3429b6`, integrati nei checkout puliti tramite fast-forward; CLI locale ricostruita. Nessun deploy. Il fallimento unit shop preesistente resta visibile.
- [Rapporto completo, limiti supportati, screenshot, log e riproduzione](docs/evidence/cherry-native/completion-2026-11-16/README.md). La qualificazione qui sotto è storica: i suoi blocchi server/protocollo/container sono risolti da questa consegna. **Prossimo: QFX-04**; non dichiarare concluso l’intero Task2.

### CH-02/08/09 — qualificazione progetti reali, 16 novembre 2026

- **VERIFICATO:** ingestione eventi reali tramite `CherryEvents`/snapshot editor; mini unit opt-in, 39 righe correnti con proof javac/classi/probe/processo. Edit non salvato invalida le posizioni; bytecode alterato produce BYTECODE_MISMATCH; ripristino esatto recupera validità. Nessuna attribuzione dinamica test inventata.
- **VERIFICATO:** Save nell’app nativa avvia tutte le categorie mini configurate, 3 unit + 25 integration + 4 functional + 4 visual PASS. Driver programmatico con import Maven e controlli reali; non sostituisce QFX-04. Log dei primi controlli falliti conservati.
- **VERIFICATO CON LIMITI:** shop-java integration 12 PASS e tre file `.exec`; suite unit 51 PASS/1 FAIL reale, coverage mancante inizialmente e disponibile con continuazione report esplicita. Contatori senza provenienza restano neutrali, anche quando precisi.
- **JUG STANDALONE:** configurazione container-only attuale eseguita integralmente, exit 0. Dati adapter 1701 pass/1 skip; summary CLI 1719 pass non equivalente a identità native verificate. Nessun evento IDE emesso dal branch sidecars richiesto. Coverage array vuote non significano uncovered. Preservati tutti i cambi utente.
- **69 regressioni mirate PASS** (24 core + 45 app). Primo tentativo Swing nel sandbox abortito da AWT; riesecuzione fuori sandbox PASS. Nessuna nuova build completa/package/deploy dichiarata.
- **INCOMPLETO:** provenienza/counter sidecar degli adapter server, separazione processo nativa, compatibilità sidecars/protocollo e policy container in TurboJ, matrice polyglot/multi-repository. Le milestone restano parziali; nessuna modifica produzione fatta per nascondere questi limiti.
- [Rapporto, comandi e prove](docs/evidence/cherry-native/qualification-2026-11-16/README.md). Nuovi driver riutilizzabili `CherryProjectQualification` e `CherryProjectSaveQualification`. QFX-04 rimane il lavoro successivo concordato; i limiti Cherry restano tracciati.

### JAVA-06 — Find Usages e test alternativi, 16 novembre 2026

- **Implementata e verificata**, baseline `56d702f` più modifiche della consegna JAVA-06. Il commit comprende Find Usages, test alternativi, fix frecce e split view; nessun deploy sito o pacchetto macOS. Esempi personali e altri file preesistenti preservati.
- `navigation.findReferences` diventa **Find Usages…**, con alias `navigation.findUsages` preservato. **Choose Related Tests / Source…** offre default e chiamanti test verificati, senza cambiare i binding dei quattro profili. Default diretto per package/modulo corretto; Enter apre il riferimento esatto; Escape/history conservano contesto.
- JDT identifica overload, annotazioni JUnit risolte/composite e contratti ereditati. Buffer unsaved partecipano; risultati obsoleti respinti anche dopo l’apertura del chooser. Javadoc e inizializzatori di tipi annidati non diventano usi dentro test. Relazione statica distinta da test eseguito o coverage.
- `./mvnw install` finale: **866 test / 124 suite PASS**, zero failure/error/skip, 03:33; include otto `UsageNavigationIT`. Precedente `clean install` PASS prima dell’ottavo controllo. Nessun conteggio del report storico in `examples/maven/target`.
- `UsagesNavigationAcceptance`: **PASS plain e Maven importato**, navigazione diretta, filtro, alternative, inversa, offset/history, Enter/Escape e invalidazione. Fixture/comandi/caret programmatici, eventi Swing reali, riattivazione foreground esplicita quando necessaria; non walkthrough umano né QFX-04.
- Registro rigenerato: **307 comandi, 29 alias, zero binding senza callback**. Include il toggle coverage già implementato ma assente dalla reference precedente. Nessuna misura prestazionale nuova.
- **Follow-up frecce nel chooser:** riprodotto il difetto con focus nel filtro (il primo harness forzava il focus sulla lista). Up/Down ora navigano i risultati anche dal filtro, senza perdere il focus di digitazione; limiti e risultati vuoti gestiti. Build app/MenuLayoutTest e acceptance nativa plain/Maven PASS; Enter dal filtro ed Escape verificati. [Prova prima/dopo](docs/evidence/find-usages-2026-11-16/arrows.json). Il reactor completo sopra precede questo fix mirato.
- **Split view nativa:** navigazione usi a sinistra, vero EditorComponent read-only a destra, sorgente completo con evidenziazione Java/numeri di riga/selezione esatta, separatore ridimensionabile e tema/font/tab coerenti. Preview isolata dal buffer modificabile; Enter apre, Escape ritorna, Tab non intrappola. 17 test mirati e acceptance plain/Maven PASS; launcher locale aggiornato. [Prove e screenshot](docs/evidence/find-usages-2026-11-16/split-view.json). Reactor completo non rieseguito per questo cambiamento UI.
- [Contratto e riproduzione](docs/find-usages.md), [log, controlli falliti conservati e hash](docs/evidence/find-usages-2026-11-16/README.md). Prossime attività: **CH-02/08/09**, poi **QFX-04**.

### Verifica successiva — correzione install, 14 novembre 2026

- **Causa:** il test `discordStagingSourcesKeepTheirLiteralsWhenAMethodIsCreatedBesideThem` leggeva il checkout JUG locale ma richiedeva il literal `50013`, non più presente nel file di input. Non era evidenza di perdita di literal da parte del refactoring.
- **Correzione REF-04:** due fixture versionate autosufficienti sotto `refactorj/refactorj-runtime/src/test/resources/quick-actions/discord/`; nessun percorso personale o skip per repository assente. Verificati membri/commenti/literal originali, file estraneo byte-per-byte, sola chiamata rinominata, compilazione Java e round-trip undo/redo. Motore di produzione invariato.
- **Verifica mirata:** `./mvnw -q -pl refactorj/refactorj-runtime -am -Dit.test=QuickActionsIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false verify` → PASS; QuickActionsIT 34 test, zero failure/error/skip.
- **Verifica completa:** `./mvnw install` → BUILD SUCCESS, 39 progetti reactor, 834 test in 122 report di suite prodotti da questa esecuzione, zero failure/error/skip. Durata Maven 3:21; fine 14 novembre 2026 alle 11:06:47 +01:00. Il conteggio completo include i 34 QuickActionsIT: non sommare la verifica mirata.
- **Artefatto:** `app/target/turboj-app.jar`, SHA-256 `caeecafb75b3b24fde029467d169812214e3f87a36f5f2c6aff4dbbdb791b671`.
- **Log locali:** `/private/tmp/turboj-quick-actions-verify.log` e `/private/tmp/turboj-install-verified.log`; riepilogo durevole in questa sezione. Sorgenti: `fc4bdac` più working tree, inclusa correzione non ancora committata.
- **Avvio della build:** `./scripts/run-dev.sh`. Nessun nuovo pacchetto macOS, deploy sito o walkthrough umano eseguito in questa correzione; gli altri criteri restano aperti.

### Preparazione della consegna — 14 novembre 2026

- Feedback utente: ultima build funzionante; suggerimenti AI percepiti come troppo aggressivi rispetto a IntelliJ. Registrato **AI-13 (P2)**, da analizzare dopo le priorità P0/P1; comportamento AI non modificato.
- `KeyboardRegistryAudit docs` sulla build verificata: **305 comandi, 29 alias, zero binding senza callback**. Reference, inventario e manifest rigenerati dal registro reale; quattro PDF aggiornati con `CheatSheetExport`.
- Export sito `node website/scripts/export-keyboard-reference.mjs`: hash sorgenti e tre documenti verificati. `node --test tests/keyboard-reference.test.mjs` in `website/`: **6 PASS**, zero failure/skip. È verifica dell’export, non nuova qualificazione dell’intero sito o deploy.
- Esclusi dal commit: esempi di lavoro manuale, `InlineTemplateAcceptance.java` locale ancora dipendente da JUG, cinque report GUI storici senza build SHA e `real-executions.json` locale con riferimenti a raw evidence ormai assenti. File preservati; gli 834 test del reactor non qualificano questi materiali.

### Comandi di riferimento per la prossima esecuzione

**Istruzioni da eseguire, non risultati di questo audit.** Preparare ambiente e fixture isolati, senza usare progetti personali o servizi esterni come controlli automatici.

| Ambito | Comando / percorso | Evidenza richiesta |
| --- | --- | --- |
| TurboJ completo | Dalla root: `./mvnw verify` | Nuovi report Surefire/Failsafe, errori/skips, SHA e toolchain |
| App/dipendenze | `./mvnw -q -pl app -am verify` | Scope esplicito, non certifica moduli esclusi dal reactor selezionato |
| Quick Actions | `./mvnw -q -pl refactorj/refactorj-runtime -am -Dit.test=QuickActionsIT,ModernizationIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false verify` | Semantica reale; smoke GUI separato |
| Cherry Java | Nel sibling, directory `java/`: `./mvnw -q verify` con JDK previsto | Commit/diff del sibling e contratto associati agli esiti |
| Cherry Rust | Nel suo workspace: `cargo test -p cherry-test -p cherry-test-core`, build/fmt secondo documentazione | Distinguere clone di controllo e sorgenti consegnati |
| Cherry nativo | `java -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.CherryNativeSmoke ../cherry-test/java/cherry-test-cli/target/cherry-test-java.jar` | Desktop macOS, fixture/porte/processi posseduti, nuovi report/screenshot |
| Smoke nativi | `QuickFixPopupSmoke`, `InlineTemplateAcceptance`, `OutputStatusSmoke`, `WorkspaceLayoutSmoke`, `InterfaceZoomSmoke` e harness first-launch | Verificare argomenti dei driver correnti; eseguirli, non soltanto compilarli; evitare contesa di focus |
| Sito | In `website/`: `npm test`, `npm run check`, `npm run build` | Export temi/reference nativi coerenti, produzione locale |
| Browser/hero | `npm run test:browser`, `npm run test:hero:browser`, `npm run test:performance` | Ambiente/base URL come in QA, payload/replay/temi/accessibilità |
| Preview | In `website/`: `npm run preview` dopo build | Verifica locale, non deploy |
| Packaging | `scripts/package-macos.sh` secondo istruzioni correnti, destinazione dedicata | Hash, runtime, Finder e test sul pacchetto prodotto |
| Igiene | `git diff --check` e revisione per ogni repository | Nessuna modifica estranea, segreto o artefatto temporaneo involontario |

`scripts/test.sh` invoca `mvn test`: non sostituisce `verify` quando servono integration test. `run-dev.sh` può riusare un JAR: non dimostra ricompilazione. Fallimenti di ambiente GUI/rete vanno registrati, non convertiti in PASS.

### Scenari trasversali di accettazione

1. **Eclipse/Calculator:** workspace→progetto plain→main→unresolved Calculator→Cmd+1/Create Class→Create JUnit Test→dipendenze→missing add→template→Save→verde. Ripetere Maven, Monokai e profilo personalizzato.
2. **Run/Save:** programma/test/implementazione aperti; Run Java/Run File/Run Tests; AutoRun e AutoTest separati/insieme; unsaved, save rifiutato, compile failure e risultato obsoleto; tab/input preservati.
3. **Tre corsie:** HEAD+staged+unstaged+unsaved, cancellazioni ai confini, test pass/fail/unknown e coverage covered/uncovered/partial/disabled/missing/incomplete/stale. Fatti indipendenti; provenienza assente mantiene neutralità.
4. **Cherry reale:** suite completa/parziale, adapter failure/cancel/switch, runtime distinti, mismatch bytecode/source, rerun noto/report. Mini/shop/shop-java/JUG con scope/infrastruttura espliciti.
5. **Editor/accessibilità:** multicaret, clipboard, selezioni rovesciate, CRLF/Unicode/undo/zoom/HiDPI, Escape/Tab/Return. Walkthrough umano distinto da eventi Swing.
6. **AI:** profilo pulito, consenso, runtime proprio/esterno, modello mancante, download/Test Completion GUI, precedenza ghost text, cancel/undo/quit senza orfani o cloud non autorizzato.
7. **Sito:** build locale, Monokai senza flash, playback/loop/dialoghi, cambio tema senza reset, responsive/reduced-motion, nessun localhost dal browser, tempi/evidenze della sessione corretta.

### Quando un'attività può essere chiusa

- Codice reale nel repository proprietario, revisione e dipendenze identificabili; non dedotto dal menu o da callback fittizi.
- Test pertinenti eseguiti con comandi/esiti/limiti; GUI reale quando il requisito riguarda focus/interazione.
- Semantica e identità corrette; risultati obsoleti/cancellati non presentati come correnti.
- Regressioni clipboard/undo/file/keymap/preferenze escluse entro lo scope dichiarato.
- Prestazioni misurate se promesse; nessuna estrapolazione da benchmark diverso.
- Documentazione/reference coerenti, evidenze durevoli e stato aggiornato qui. Packaging, commit/push e produzione verificati separatamente quando inclusi.

Se resta un requisito, mantenere `PARZIALE`, `INCOMPLETA` o `DA VERIFICARE`, non «completato».

## 8. Manutenzione del canovaccio

1. Una sola lista operativa: questo file. Ogni richiesta nuova riceve ID stabile e criterio di chiusura, nella sezione pertinente.
2. Non duplicare dipendenze: TEST-03 richiama CH-05/06, non crea un secondo runner o modello di attribuzione.
3. Registrare cambi di default/scope/priorità con motivazione; un prompt storico non annulla automaticamente una decisione successiva.
4. Separare codice, test, artefatto locale, commit/push e produzione. Aggiornare data/baseline a ogni consegna.
5. Conservare la storia: aggiungere nuove evidenze senza riscrivere i vecchi risultati per farli sembrare attuali.
6. Preservare il working tree: distinguere lavoro preesistente/fixture/artefatti prima di commit o build. Questo incarico documentale non esegue reset o pubblicazioni.
7. Tenere il canovaccio navigabile: sommario/stato/backlog qui, protocollo e log completi nei documenti specialistici collegati.

## 9. Indice delle fonti consolidate

Il censimento seguente rende rintracciabili specifiche, decisioni ed evidenze. **La presenza nell'indice non certifica la verità attuale di ogni frase del documento**: le divergenze rilevate sono in §4 e GOV-03. Guide dei moduli, prompt, report datati e cataloghi generati hanno ruoli distinti.

Esclusi dall'elenco per-file: output build/cache, copie pubbliche generate del sito, licenze vendorizzate e contenuti delle fixture esterne. Guide/manifest rimangono indicizzati; benchmark ed evidenze non Markdown sono riferiti per archivio, senza duplicare log e screenshot.

<!-- SOURCE_INDEX:START -->
**150 documenti Markdown di supporto censiti**, oltre a questo canovaccio. I cataloghi di comandi e temi restano generati; le loro singole righe non vengono ricopiate come attività.

### Documentazione generale e nativa

- [README.md](README.md)
- [docs/acceptance.md](docs/acceptance.md)
- [docs/architecture.md](docs/architecture.md)
- [docs/architecture/cherry-test-turboj-integration.md](docs/architecture/cherry-test-turboj-integration.md)
- [docs/cli.md](docs/cli.md)
- [docs/compiler.md](docs/compiler.md)
- [docs/dependencies.md](docs/dependencies.md)
- [docs/eclipse-workflow-parity.md](docs/eclipse-workflow-parity.md)
- [docs/ecosystem-release-1.0.7.md](docs/ecosystem-release-1.0.7.md)
- [docs/evidence/first-launch/README.md](docs/evidence/first-launch/README.md)
- [docs/first-launch-keyboard-acceptance.md](docs/first-launch-keyboard-acceptance.md)
- [docs/first-launch-to-first-test-prompt.md](docs/first-launch-to-first-test-prompt.md)
- [docs/formatting-comparison.md](docs/formatting-comparison.md)
- [docs/formatting.md](docs/formatting.md)
- [docs/inline-templates.md](docs/inline-templates.md)
- [docs/interaction-release-1.0.10.md](docs/interaction-release-1.0.10.md)
- [docs/java-intelligence-performance.md](docs/java-intelligence-performance.md)
- [docs/java-intelligence-release-1.0.3.md](docs/java-intelligence-release-1.0.3.md)
- [docs/java-intelligence.md](docs/java-intelligence.md)
- [docs/javafmt-integration.md](docs/javafmt-integration.md)
- [docs/javafmt-release-1.0.6.md](docs/javafmt-release-1.0.6.md)
- [docs/keyboard-accessibility-audit.md](docs/keyboard-accessibility-audit.md)
- [docs/keyboard-command-inventory.md](docs/keyboard-command-inventory.md)
- [docs/keyboard-conflicts.md](docs/keyboard-conflicts.md)
- [docs/keyboard-first-design.md](docs/keyboard-first-design.md)
- [docs/keyboard-performance.md](docs/keyboard-performance.md)
- [docs/keyboard-shortcut-reference.md](docs/keyboard-shortcut-reference.md)
- [docs/keymap-comparison.md](docs/keymap-comparison.md)
- [docs/keymap-turboj-macos.md](docs/keymap-turboj-macos.md)
- [docs/licenses.md](docs/licenses.md)
- [docs/limitations.md](docs/limitations.md)
- [docs/maven-ascii-release-1.0.8.md](docs/maven-ascii-release-1.0.8.md)
- [docs/maven-open-copy-release-1.0.9.md](docs/maven-open-copy-release-1.0.9.md)
- [docs/menus-turboj.md](docs/menus-turboj.md)
- [docs/native-layout-preview-2026-10-25.md](docs/native-layout-preview-2026-10-25.md)
- [docs/output-and-status.md](docs/output-and-status.md)
- [docs/output-command-line.md](docs/output-command-line.md)
- [docs/performance.md](docs/performance.md)
- [docs/project-explorer.md](docs/project-explorer.md)
- [docs/quick-fix-quick-assist.md](docs/quick-fix-quick-assist.md)
- [docs/session-2026-10-23.md](docs/session-2026-10-23.md)
- [docs/shortcuts.md](docs/shortcuts.md)
- [docs/stdin-fix.md](docs/stdin-fix.md)
- [docs/theme-catalog-inventory.md](docs/theme-catalog-inventory.md)
- [docs/theme-catalog.md](docs/theme-catalog.md)
- [docs/themes-release-1.0.4.md](docs/themes-release-1.0.4.md)
- [docs/themes.md](docs/themes.md)

### AI

- [docs/ai-completion.md](docs/ai-completion.md)
- [docs/ai-credentials.md](docs/ai-credentials.md)
- [docs/ai-local-editing-latency.md](docs/ai-local-editing-latency.md)
- [docs/ai-local-validation.md](docs/ai-local-validation.md)
- [docs/ai-providers.md](docs/ai-providers.md)
- [docs/ai-release-1.0.5.md](docs/ai-release-1.0.5.md)
- [docs/ai/autocomplete-current-architecture.md](docs/ai/autocomplete-current-architecture.md)
- [docs/ai/autocomplete-performance.md](docs/ai/autocomplete-performance.md)
- [docs/ai/autocomplete-ranking.md](docs/ai/autocomplete-ranking.md)
- [docs/ai/autocomplete-troubleshooting.md](docs/ai/autocomplete-troubleshooting.md)
- [docs/ai/autocomplete-validation.md](docs/ai/autocomplete-validation.md)
- [docs/ai/local-model-manager.md](docs/ai/local-model-manager.md)
- [docs/ai/provider-compatibility.md](docs/ai/provider-compatibility.md)
- [turbo-ai/CHANGELOG.md](turbo-ai/CHANGELOG.md)
- [turbo-ai/README.md](turbo-ai/README.md)

### CommandJ

- [commandj/CHANGELOG.md](commandj/CHANGELOG.md)
- [commandj/CONTRIBUTING.md](commandj/CONTRIBUTING.md)
- [commandj/README.md](commandj/README.md)
- [commandj/THIRD_PARTY.md](commandj/THIRD_PARTY.md)
- [commandj/docs/API.md](commandj/docs/API.md)
- [commandj/docs/ARCHITECTURE.md](commandj/docs/ARCHITECTURE.md)
- [commandj/docs/KEYMAPS.md](commandj/docs/KEYMAPS.md)
- [commandj/docs/PERFORMANCE.md](commandj/docs/PERFORMANCE.md)
- [commandj/docs/RELEASE.md](commandj/docs/RELEASE.md)
- [commandj/docs/TESTING.md](commandj/docs/TESTING.md)

### RefactorJ

- [refactorj/CHANGELOG.md](refactorj/CHANGELOG.md)
- [refactorj/CONTRIBUTING.md](refactorj/CONTRIBUTING.md)
- [refactorj/README.md](refactorj/README.md)
- [refactorj/docs/ADAPTERS.md](refactorj/docs/ADAPTERS.md)
- [refactorj/docs/ARCHITECTURE.md](refactorj/docs/ARCHITECTURE.md)
- [refactorj/docs/FEATURES.md](refactorj/docs/FEATURES.md)
- [refactorj/docs/INTEGRATION.md](refactorj/docs/INTEGRATION.md)
- [refactorj/docs/JDT.md](refactorj/docs/JDT.md)
- [refactorj/docs/LICENSES.md](refactorj/docs/LICENSES.md)
- [refactorj/docs/PERFORMANCE.md](refactorj/docs/PERFORMANCE.md)
- [refactorj/docs/RELEASE.md](refactorj/docs/RELEASE.md)
- [refactorj/docs/TRANSACTIONS.md](refactorj/docs/TRANSACTIONS.md)
- [refactorj/refactorj-intellij/README.md](refactorj/refactorj-intellij/README.md)
- [refactorj/refactorj-vscode/README.md](refactorj/refactorj-vscode/README.md)

### MavenJ

- [mavenj/CHANGELOG.md](mavenj/CHANGELOG.md)
- [mavenj/README.md](mavenj/README.md)
- [mavenj/docs/ARCHITECTURE.md](mavenj/docs/ARCHITECTURE.md)
- [mavenj/docs/CLI.md](mavenj/docs/CLI.md)
- [mavenj/docs/COMPILER_INTEGRATION.md](mavenj/docs/COMPILER_INTEGRATION.md)
- [mavenj/docs/DEPENDENCY_RESOLUTION.md](mavenj/docs/DEPENDENCY_RESOLUTION.md)
- [mavenj/docs/EFFECTIVE_POM.md](mavenj/docs/EFFECTIVE_POM.md)
- [mavenj/docs/MCP.md](mavenj/docs/MCP.md)
- [mavenj/docs/MULTI_MODULE.md](mavenj/docs/MULTI_MODULE.md)
- [mavenj/docs/PERFORMANCE.md](mavenj/docs/PERFORMANCE.md)
- [mavenj/docs/PROJECT_IMPORT.md](mavenj/docs/PROJECT_IMPORT.md)
- [mavenj/docs/RELEASE.md](mavenj/docs/RELEASE.md)
- [mavenj/docs/SECURITY.md](mavenj/docs/SECURITY.md)
- [mavenj/docs/TEST_INTEGRATION.md](mavenj/docs/TEST_INTEGRATION.md)
- [mavenj/docs/TOOLCHAINS.md](mavenj/docs/TOOLCHAINS.md)
- [mavenj/docs/TURBOJ.md](mavenj/docs/TURBOJ.md)
- [mavenj/docs/VALIDATION.md](mavenj/docs/VALIDATION.md)
- [mavenj/examples/README.md](mavenj/examples/README.md)

### TurboTest

- [turbotest/CHANGELOG.md](turbotest/CHANGELOG.md)
- [turbotest/README.md](turbotest/README.md)

### JavaFMT (sospeso)

- [javafmt/CHANGELOG.md](javafmt/CHANGELOG.md)
- [javafmt/CONTRIBUTING.md](javafmt/CONTRIBUTING.md)
- [javafmt/README.md](javafmt/README.md)
- [javafmt/docs/ARCHITECTURE.md](javafmt/docs/ARCHITECTURE.md)
- [javafmt/docs/CLI-VALIDATION.md](javafmt/docs/CLI-VALIDATION.md)
- [javafmt/docs/CLI.md](javafmt/docs/CLI.md)
- [javafmt/docs/COMPATIBILITY.md](javafmt/docs/COMPATIBILITY.md)
- [javafmt/docs/INTEGRATION.md](javafmt/docs/INTEGRATION.md)
- [javafmt/docs/LICENSES.md](javafmt/docs/LICENSES.md)
- [javafmt/docs/PERFORMANCE.md](javafmt/docs/PERFORMANCE.md)
- [javafmt/docs/STYLE.md](javafmt/docs/STYLE.md)
- [javafmt/docs/VALIDATION.md](javafmt/docs/VALIDATION.md)
- [javafmt/docs/measurements/2026-10-24/jmh-table.md](javafmt/docs/measurements/2026-10-24/jmh-table.md)
- [javafmt/javafmt-intellij/README.md](javafmt/javafmt-intellij/README.md)
- [javafmt/javafmt-test-fixtures/README.md](javafmt/javafmt-test-fixtures/README.md)
- [javafmt/javafmt-test-fixtures/openjdk/README.md](javafmt/javafmt-test-fixtures/openjdk/README.md)
- [javafmt/javafmt-vscode/README.md](javafmt/javafmt-vscode/README.md)
- [javafmt/javafmt-vscode/THIRD_PARTY.md](javafmt/javafmt-vscode/THIRD_PARTY.md)

### Website e catture

- [docs/website-demo-feasibility.md](docs/website-demo-feasibility.md)
- [docs/website-handoff-2026-10-24.md](docs/website-handoff-2026-10-24.md)
- [docs/website-hero-release-2026-10-25.md](docs/website-hero-release-2026-10-25.md)
- [docs/website-hero-validation-2026-10-25.md](docs/website-hero-validation-2026-10-25.md)
- [docs/website-native-console-finale.md](docs/website-native-console-finale.md)
- [website/ASSETS.md](website/ASSETS.md)
- [website/DEPLOYMENT.md](website/DEPLOYMENT.md)
- [website/QA.md](website/QA.md)
- [website/README.md](website/README.md)
- [website/demo/VERIFICATION.md](website/demo/VERIFICATION.md)
- [website/demo/capture/CONSOLE_FINALE.md](website/demo/capture/CONSOLE_FINALE.md)
- [website/demo/capture/CONTROL_RUNS.md](website/demo/capture/CONTROL_RUNS.md)
- [website/demo/capture/HERO_STORY.md](website/demo/capture/HERO_STORY.md)
- [website/demo/capture/HERO_STORY_SCHEMA.md](website/demo/capture/HERO_STORY_SCHEMA.md)
- [website/demo/capture/README.md](website/demo/capture/README.md)
- [website/demo/capture/SPRING_SESSION.md](website/demo/capture/SPRING_SESSION.md)
- [website/demo/evidence/controls/README.md](website/demo/evidence/controls/README.md)
- [website/demo/projects/console-finale/README.md](website/demo/projects/console-finale/README.md)
- [website/demo/projects/hero-story/README.md](website/demo/projects/hero-story/README.md)
- [website/demo/projects/spring-hello/README.md](website/demo/projects/spring-hello/README.md)
- [website/docs/interactive-hero.md](website/docs/interactive-hero.md)

### Esempi, fixture e protocolli

- [examples/README.md](examples/README.md)
- [examples/maven/README.md](examples/maven/README.md)
- [examples/plain-java/README.md](examples/plain-java/README.md)
- [integration-tests/fixtures/cherry-native/README.md](integration-tests/fixtures/cherry-native/README.md)
- [worker/docs/protocol.md](worker/docs/protocol.md)

### Archivi, contratti e sorgenti da consultare insieme ai documenti

- [benchmarks/results](benchmarks/results/): risultati datati; ogni misura mantiene hardware, metodo e revisione propri.
- [docs/keyboard-evidence](docs/keyboard-evidence/), [docs/evidence/first-launch](docs/evidence/first-launch/), [docs/evidence/cherry-native](docs/evidence/cherry-native/), [docs/evidence/output-command-line](docs/evidence/output-command-line/): eventi, screenshot e report; verificare quali file sono tracciati e quali riferimenti temporanei vanno preservati.
- [website/demo/evidence](website/demo/evidence/): sessioni, controlli falliti, sorgenti e hash; la prima cattura esterna e il finale nativo restano distinguibili.
- [docs/keyboard-reference-sources.json](docs/keyboard-reference-sources.json), [docs/runtime-dependencies.tsv](docs/runtime-dependencies.tsv): provenienza degli export e dipendenze; non sostituiscono le verifiche della build corrente.
- [app/src/main/java/dev/turboj/app/TurboFrame.java](app/src/main/java/dev/turboj/app/TurboFrame.java), [MenuLayout](app/src/main/java/dev/turboj/app/MenuLayout.java), [BundledKeymaps](commandj/commandj-keymap/src/main/java/dev/turboj/commandj/keymap/BundledKeymaps.java): fonte corrente per integrazione, menu e profili, incluse le modifiche della consegna del 14 novembre.
- [core/src/main/java/dev/turboj/testing/intelligence](core/src/main/java/dev/turboj/testing/intelligence/), [app/src/main/java/dev/turboj/testing](app/src/main/java/dev/turboj/testing/), [LineGutter](app/src/main/java/dev/turboj/ui/intelligence/LineGutter.java): modello, lifecycle, Git e presentazione Cherry.
- Repository sibling Cherry Test: `../cherry-test/docs/native-ide-events.md`, CLI Java e report Java/Rust consegnati in `302325e8`. Collegamento di workspace, non dipendenza inclusa in questo repository; riproduzione CH-01 vincolata ai commit.
- JUG Lugano: configurazioni e launcher del repository esterno usato dall'indagine, con revisioni/esecuzioni registrate nelle evidenze Cherry. La matrice concordata CH-09 è verificata nella consegna del 16 novembre.
<!-- SOURCE_INDEX:END -->
