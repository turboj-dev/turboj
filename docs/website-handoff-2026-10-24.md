# TurboJ — Brief fattuale per i contenuti del sito

Periodo: **23–24 ottobre 2026**, fuso Europe/Zurich. Stato applicativo: build
macOS **1.0.10**, commit `5d801f8`. Questo documento riassume il lavoro eseguito;
le richieste iniziali non sono una prova che ogni variante prevista sia supportata.

Il sito attuale è in inglese. Usa questo brief per proporre testi in inglese per
homepage, sezioni funzionalità, ecosistema e FAQ. Mantieni il tono concreto,
essenziale e tecnico quanto basta. Non trasformare gli obiettivi di progetto in
garanzie o funzionalità già pubblicate.

**1. Identità e messaggio principale**

TurboJ è un ambiente Java desktop per macOS, ispirato agli editor degli anni
Novanta: un'unica finestra, interazione da tastiera, testo monospaziato e bordi
ASCII/box drawing. L'interfaccia minimale contiene strumenti Java moderni per
scrivere, comprendere, trasformare, compilare, eseguire e verificare codice.

La semplicità riguarda l'esperienza d'uso: ora sono supportati anche progetti Maven
e reactor multi-modulo, oltre ai singoli file e agli esperimenti rapidi.

Messaggi guida già scelti:

- **TurboJ — Minimal interface. Maximum Java intelligence.**
- **Native inside TurboJ, standalone everywhere else.**

TurboJ è un'applicazione Java/Swing/Java2D, non un editor web incorporato. Il bundle
include runtime e compilatore Java 25. I progetti Maven conservano il proprio
livello Java e possono richiedere un JDK/toolchain aggiuntivo. Non descrivere tutti
i progetti come eseguibili senza alcuna configurazione.

**2. Cosa è stato realizzato il 23 ottobre**

- Creato l'editor desktop con evidenziazione Java, numeri di riga, selezione,
  copia/incolla, undo/redo, ricerca, indentazione, commenti e gestione Unicode.
- Aggiunti compilazione in memoria, diagnostica e navigazione agli errori.
- Implementati Fast Run con JVM residente, Isolated Run, stdout/stderr, input
  interattivo per programmi come quelli con `Scanner`, Stop e gestione dei timeout.
- Integrato JShell nella parte inferiore della stessa finestra: espressioni,
  dichiarazioni multilinea, cronologia, completamento, valutazione della selezione,
  arresto e reset.
- Creata la CLI `tj` con comandi per aprire, verificare, compilare, eseguire,
  valutare espressioni e diagnosticare l'ambiente.
- Aggiunti salvataggio con controllo delle modifiche esterne, recupero del buffer
  e conservazione dei buffer non salvati durante la navigazione.
- Implementata l'intelligenza Java semantica con JDT: completamento inline,
  alternative e overload, navigazione alle dichiarazioni, firme dei metodi,
  parametro attivo, Javadoc e navigazione avanti/indietro.
- Costruiti e provati i primi pacchetti macOS con runtime incluso.
- Creato e pubblicato il sito statico turboj.dev, con repository pubblico collegato,
  screenshot autentico, demo interattiva illustrativa e controlli responsive/accessibilità.

**3. Evoluzione del 24 ottobre: un'unica interfaccia coerente**

L'interazione avviene dentro il frame principale. Dialoghi, selettori, palette,
impostazioni, preview di refactoring e pannelli condividono lo stile dell'editor:
testo monospaziato, bordi a caratteri, controlli semplici e colori del tema.

Sono stati eliminati i titoli e i contorni da finestre desktop interne, le ombre
e gli effetti che facevano sembrare i dialoghi sovrapposti a un'altra applicazione.
Menu, editor, output ed explorer usano la stessa impostazione visiva.

Novità della build 1.0.10:

- **Una sola barra di stato in basso**, con l'esito importante prima dei dettagli.
  Informazioni estese e suggerimenti sono disponibili nel tooltip.
- **Smaller Font / Larger Font applicati a tutta l'interfaccia**: editor, menu,
  shortcut, output, explorer, popup e dialoghi. I testi mantengono le proporzioni.
- Colori coerenti e leggibili anche per shortcut, elementi disabilitati e selezioni.
- Menu in eccedenza accessibili da **More** nelle finestre strette.
- Indicatore opzionale dello shortcut eseguito nell'angolo inferiore destro
  dell'editor, con sfondo nel colore accent del tema. Supporta personalizzazioni
  e sequenze; non mostra il normale testo digitato o le password.
- Copia del testo dall'output mediante Cmd+C o Edit → Copy; l'arrivo di nuovo
  output preserva la selezione.

**4. Temi condivisi tra app e sito**

Il catalogo comprende **103 temi**: Turbo Classic, Modern Dark, Modern Light e
100 adattamenti curati di temi esistenti, con provenienza e licenze documentate.

Il selettore permette ricerca, anteprima, scorrimento, conferma e annullamento.
Random Theme usa un rullo ASCII con anteprima delle palette. App e sito derivano
le palette dalla stessa fonte; le preferenze dei due ambienti sono indipendenti,
senza account o sincronizzazione remota.

Tema visivo, dimensione dei caratteri e profilo di tastiera sono preferenze distinte.
Non chiamare il catalogo una classifica dei temi più popolari.

**5. Intelligenza Java e AI opzionale**

Il completamento semantico, la navigazione alle dichiarazioni, la documentazione
e le firme dei metodi funzionano senza AI. Le funzioni semantiche e RefactorJ
condividono il workspace JDT; non viene costruito un indice completo separato per
ogni funzione. I risultati obsoleti vengono scartati.

L'AI è una funzione opzionale, disattivata inizialmente. Mostra suggerimenti
inline, anche multilinea; Tab accetta, Escape annulla e l'inserimento ha un unico
undo. Le proposte semantiche hanno priorità. Non è stata aggiunta una chat laterale.

Esistono adattatori locali Ollama e llama.cpp FIM e un adattatore OpenAI Responses
con consenso esplicito per il cloud. Il contesto AI è limitato al buffer corrente;
non è un agente autonomo che comprende e modifica tutto il repository.

La generazione locale con Ollama è stata provata con un modello reale: 12 esempi
generati, compilati ed eseguiti con esito atteso, più una prova nell'app confezionata.
Questo non dimostra correttezza generale del codice generato. Il cloud ha test di
protocollo; **l'inferenza cloud con account reale non è stata verificata**.
Modelli e runtime di inferenza non sono inclusi nel DMG. Le credenziali cloud sono
gestite tramite macOS Keychain.

**6. Ecosistema di librerie Java indipendenti**

| Componente | Funzione effettiva | Integrazione in TurboJ |
| --- | --- | --- |
| TurboJ | Editor e applicazione macOS | Riunisce i componenti |
| FormatJ / javafmt | Formattazione Java canonica | API Java diretta |
| RefactorJ | Trasformazioni semantiche e code action | API Java e workspace condiviso |
| CommandJ | Comandi, shortcut, keymap e palette | API Java diretta |
| TurboTest | Compilazione test ed esecuzione JUnit | API Java e worker di esecuzione |
| MavenJ | Modello Maven, dipendenze, moduli e classpath | API Java diretta |

CLI, LSP e MCP sono adattatori esterni dove implementati. TurboJ non li usa per
formattare, fare refactoring, gestire comandi o importare normalmente il modello
Maven. Non dire però che l'intero prodotto non utilizza mai processi: programmi,
test, JShell, inferenza opzionale e build Maven esplicite hanno esigenze diverse.

**FormatJ / javafmt.** Formatter indipendente con stile canonico: quattro spazi,
parentesi K&R e obiettivo di 100 colonne. Formatta documento o selezione; Format
on Save è opzionale. Preserva commenti e letterali, applica modifiche con undo e
segnala il codice non valido senza riscriverlo. Organize Imports è un'azione
semantica separata. Sono disponibili CLI e adattatori esterni.

**RefactorJ.** Sono implementate **33 operazioni semantiche e code action**:
rename, estrazioni, inline, cambio firma, move, safe delete, interfacce e gerarchie,
incapsulamento, factory, trasformazioni lambda/method reference, modernizzazione,
import e quick fix. Comprendono controllo di applicabilità, conflitti, anteprima,
buffer non salvati, modifiche tra file, controllo delle versioni e undo/redo.
L'elenco non implica che ogni possibile selezione Java sia trasformabile: le
condizioni non sicure vengono rifiutate. Reflection e consumatori esterni restano
limiti dell'analisi statica. Le transazioni multi-file sono recuperabili, non una
promessa di atomicità dell'intero filesystem.

**CommandJ.** Registro centrale, dispatch contestuale e palette locale con ricerca
fuzzy. Quattro keymap macOS: IntelliJ, Eclipse, VS Code e TurboJ Classic.
Personalizzazione, più binding, chord, rilevamento dei conflitti, ereditarietà,
reset e import/export JSON. Nuove installazioni: IntelliJ macOS; quelle precedenti
conservano Classic salvo scelta diversa. Non presentare F2/F9/Ctrl+F9 come
shortcut universali: appartengono al profilo Classic.

**7. MavenJ: progetti reali, senza build Maven a ogni modifica**

Si può aprire una cartella Maven o direttamente `pom.xml`. MavenJ usa le API
ufficiali Maven e Maven Resolver per effective POM, parent, proprietà, BOM,
dependency management, profili, dipendenze transitive, scope, impostazioni,
repository e reactor multi-modulo.

Il progetto importato fornisce source/test roots, risorse, classpath, module path,
configurazione Java e toolchain ai sistemi già presenti in TurboJ. I moduli usano
gli output correnti dei moduli dipendenti senza richiedere `mvn install` dopo
ogni modifica. Sono presenti explorer compatto richiudibile, diagnostica XML/POM,
azioni POM con anteprima e sincronizzazione delle configurazioni.

Il primo import può scaricare le dipendenze da Maven Central o dai repository e
mirror configurati. Offline esplicito e restrizioni dei repository restano rispettati.
Le dipendenze mancanti producono diagnostica; l'import non esegue automaticamente
wrapper, plugin o annotation processor controllati dal progetto.

**Fast Compile** usa il compilatore residente di TurboJ. **Maven Build** avvia,
su richiesta, il vero lifecycle e rispetta il wrapper/distribuzione del progetto.
Sono disponibili validate, compile, test, package, verify, install, clean,
dependency tree, effective POM e goal configurati, con output e annullamento.

Workflow verificato: aprire il POM/cartella, attendere le dipendenze, usare
**Run → Run** e **Test → Run All Tests**. Run trova il main del progetto Maven
anche se è ancora aperto il POM; offre una scelta se ci sono più entry point.

I livelli Java e le toolchain del progetto non vengono sostituiti automaticamente
con Java 25. Gli annotation processor richiedono fiducia e un'azione esplicita
isolata. Plugin arbitrari, provider Surefire/Failsafe personalizzati e configurazioni
non riproducibili nel percorso nativo richiedono la build Maven reale. Non dichiarare
supporto completo alle semantiche Maven 4 o import Gradle.

**8. TurboTest e automazione al salvataggio**

Esecuzione JUnit per metodo, classe o progetto; rerun dei fallimenti, Stop e vista
risultati. I progetti Maven usano dipendenze e classpath di test effettivi. La
selezione automatica opera conservativamente sui moduli interessati; non è
un'analisi di copertura perfetta a livello di singolo metodo.

Il resoconto mostra stato, durata, totali, fallimenti e stack trace, con colori
del tema e testo copiabile. Gli stati rimangono espliciti anche senza colore.
Le note informative duplicate del compilatore sono nei dettagli.

**Auto Run on Save** e **Auto Test on Save** sono opzioni indipendenti, ricordate
e inizialmente disattivate. Prima si salva il sorgente Java, poi si compila il
workspace; soltanto una compilazione riuscita e ancora attuale può avviare le
azioni abilitate. Funzionano sia con Maven sia senza Maven. Errori di salvataggio,
errori di compilazione e generazioni obsolete non avviano l'esecuzione.

Run e test restano indipendenti anche nell'annullamento. Un programma in attesa
di stdin non blocca la suite. Le preferenze non concedono automaticamente fiducia
a un progetto Maven sconosciuto. L'opzione precedente **Auto Test on Changes**
rimane distinta: verificata sui cambiamenti Maven e dopo refactoring applicati.

Sono stati aggiunti due progetti di esempio, con e senza Maven, basati sullo stesso
piccolo calcolatore e con due unit test JUnit ciascuno.

**9. Correzioni e compatibilità**

- Risolta l'inizializzazione semantica che accorpava progetti di esempio annidati
  con identità sorgente duplicate; rispettati i confini dei progetti.
- Migliorata la diagnostica delle cause reali dei problemi di inizializzazione.
- Corretto il primo import Maven, che non deve partire offline e lasciare
  dipendenze mancanti su un'installazione nuova.
- Resa operativa la copia dall'output e preservata la selezione durante lo streaming.
- Salva con nome suggerisce il nome Java dedotto per un buffer senza nome.
  La regola `public class HelloWorld` → `HelloWorld.java` continua a valere.
- TurboJ e i nuovi moduli adottano il namespace **dev.turboj**.
- Il formatter conserva per compatibilità package **dev.javafmt**, coordinate
  `dev.javafmt:javafmt-core:0.1.0` e comando **javafmt**.

**10. Adattatori, distribuzione e lavoro ancora aperto**

RefactorJ dispone di API, CLI, LSP, MCP e integrazioni IntelliJ/VS Code.
Gli adattatori RefactorJ e javafmt sono stati provati in host reali IntelliJ e
VS Code; questo non garantisce ogni versione futura degli IDE.
MavenJ dispone di API headless, CLI e adattatore MCP opzionale.

Gli artefatti indipendenti e i plugin sono costruiti localmente: **non risultano
pubblicazioni su Maven Central o nei marketplace**. Il codice è nel repository
pubblico `https://github.com/turboj-dev/turboj`. Le directory dei moduli sono ancora
all'interno del repository principale.

La rinomina definitiva del progetto javafmt in **FormatJ/formatj** e lo spostamento
dei tool in directory sorelle di `turboj/` sono nella TODO, non già completati.

Il pacchetto macOS corrente è 1.0.10, con runtime incluso. È una preview di sviluppo
locale, senza firma Developer ID o notarizzazione. Validazione eseguita su macOS
Apple Silicon; non dichiarare una distribuzione Intel, Windows o Linux verificata.
Debugger integrato, import Gradle e updater rimangono fuori dalle capacità consegnate.

**11. Stato del sito e indicazioni editoriali**

Il sito è statico, con asset/font locali, senza analytics o framework idratato.
Sono documentati deploy su turboj.dev, inclusi gli aggiornamenti ecosistema e
MavenJ del 24 ottobre. Un push GitHub non equivale a un nuovo deploy del sito.

La demo HelloWorld del browser è un replay illustrativo: non compila Java.
Lo screenshot autentico e le sue misure sono separati dall'animazione. Non usare
i tempi della demo come benchmark né una cache hit come tempo di compilazione.

I contenuti da aggiornare con priorità sono: workflow Maven, JUnit e automazione
al salvataggio, interfaccia ASCII unificata, font globali, keymap indipendenti,
palette comandi ed ecosistema riutilizzabile. La vecchia descrizione limitata ai
piccoli file non rappresenta più tutta la capacità implementata. Restare comunque
sobri: nessuna promessa di parità completa con IntelliJ/Eclipse.

Struttura consigliata per il nuovo copy: hero breve; workflow Write → Compile →
Run → Test; Java intelligence; Maven; refactoring e formattazione; tastiera e temi;
AI opzionale; ecosistema; FAQ con requisiti, disponibilità e limiti reali.

Non inventare download pubblici, numeri di utenti, ranking, affiliazioni con Apache,
JetBrains o Microsoft, superiorità su altri IDE o tempi garantiti. Le misure locali
hanno hardware, dimensione del progetto e confini della misurazione specifici.

**12. Evidenze da consultare**

La verifica finale disponibile comprende **421 casi Surefire e 77 Failsafe**
(498 complessivi), senza errori/fallimenti/skips nei report; i primi conteggi
419/421 del report 1.0.10 omettevano Failsafe e sono stati corretti. Le verifiche GUI
sono separate, non vanno sommate ai test JUnit né ai totali delle release precedenti.
Una prova dello shortcut chord ha avuto un fallimento non riprodotto al tentativo
successivo, riportato nel documento di release. Nessuna promessa di assenza di bug.

Fonti locali principali:

- `docs/session-2026-10-23.md`
- `docs/java-intelligence-release-1.0.3.md`
- `docs/themes-release-1.0.4.md`
- `docs/ai-release-1.0.5.md` e `docs/ai-local-validation.md`
- `docs/javafmt-release-1.0.6.md`
- `docs/ecosystem-release-1.0.7.md`
- `docs/maven-ascii-release-1.0.8.md`
- `docs/maven-open-copy-release-1.0.9.md`
- `docs/interaction-release-1.0.10.md`
- `refactorj/docs/FEATURES.md` e `refactorj/docs/ADAPTERS.md`
- `mavenj/docs/VALIDATION.md` e `mavenj/docs/EFFECTIVE_POM.md`
- `javafmt/docs/COMPATIBILITY.md`
- `website/DEPLOYMENT.md`, `website/QA.md` e `TODO.md`

I limiti nei report storici descrivono il momento della rispettiva release:
ad esempio Maven e JUnit mancavano nei primi report ma sono stati aggiunti dopo.
Per il copy usare lo stato più recente, mantenendo i limiti specifici ancora validi.
