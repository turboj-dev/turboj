package dev.turboj.app;

import dev.turboj.commandj.keymap.KeySequence;
import java.util.*;

/** Stable, explicitly allocated supplemental shortcuts. Never renumber when adding an action.
 * Existing profile shortcuts take precedence. Release Ctrl+Cmd+K, then type the two letters.
 * These are TurboJ additions, not official IntelliJ/Eclipse/VS Code defaults. */
public final class KeyboardDefaults {
    private KeyboardDefaults() {}
    public static final String PREFIX = "CTRL+META+K";
    public static final Map<String, KeySequence> SUPPLEMENTS = parse();
    private static Map<String, KeySequence> parse() {
        var result = new LinkedHashMap<String, KeySequence>();
        var used = new HashSet<String>();
        for (String line : CATALOG.lines().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList()) {
            String[] entry = line.split("\\s+");
            if (!entry[0].matches("[A-Z]{2}") || !used.add(entry[0])) throw new IllegalStateException("Duplicate/invalid shortcut: " + line);
            if (result.put(entry[1], KeySequence.parse(PREFIX + ", " + entry[0].charAt(0) + ", " + entry[0].charAt(1))) != null)
                throw new IllegalStateException("Duplicate action: " + line);
        }
        return Collections.unmodifiableMap(result);
    }
    private static final String CATALOG = """
            # A: AI
            AA ai.another
            AC ai.cloud
            AD ai.diagnostics
            AB ai.disabled
            AE ai.download
            AL ai.larger
            AF ai.local
            AR ai.request
            AG ai.retry
            AS ai.settings
            AT ai.test
            # L: Live templates
            LN editor.expandLiveTemplate
            LO editor.insertLiveTemplate
            LP settings.liveTemplates
            # C: Completion
            CC complete
            CA completion.accept
            CL completion.acceptLine
            CW completion.acceptWord
            CD completion.dismiss
            CN completion.next
            CP completion.parameterInformation
            CB completion.previous
            CS completion.smart
            CE documentation
            CF documentation.focus
            CG smart.action
            # B: Build and execution
            BC compile
            BP compileProject
            BE error
            BI isolated
            BM make
            BR run
            BO run.clearOutputBefore
            BA run.configuration
            BB run.context
            BS run.onSave
            BD stop
            # J: JShell and console
            JC clear
            JA console.cancelCommand
            JL console.commandLine
            JB console.copy
            JE eof
            JS evaluateSelection
            JD evaluateStatement
            JO output
            JR repl
            JF replReset
            # F: Files
            FC file.close
            FN file.newFile
            FJ file.newJavaClass
            FP file.newJavaProject
            FM file.newMavenProject
            FR file.reopenClosed
            FS file.saveAll
            FA new
            FO open
            FF openFolder
            FQ quit
            FB save
            FD saveAs
            FE workspace.select
            # K: Keymap and settings
            KB escape
            KC help
            KH help.cheatSheet
            KK keymap
            KE keymap.profile.eclipse-macos
            KI keymap.profile.intellij-macos
            KT keymap.profile.turboj-macos
            KV keymap.profile.vscode-macos
            KM menu
            KD metrics
            KP palette
            KG settings.general
            KS settings.shortcutSymbols
            KA keymap.assignMissing
            # L: Physical lines
            LD deleteLine
            LL duplicateLine
            LA editor.autoIndentLines
            LC editor.copyLineDown
            LU editor.copyLineUp
            LT editor.deleteToLineEnd
            LS editor.deleteToLineStart
            LI editor.insertLineAbove
            LB editor.insertLineBelow
            LJ editor.joinLines
            LR editor.reverseSelectedLines
            LE editor.sortSelectedLines
            LF editor.splitLine
            LG indent
            LM moveLineDown
            LH moveLineUp
            LK unindent
            # S: Selection and carets
            SA editor.addCaretAbove
            SC editor.addCaretBelow
            SE editor.expandSelection
            SR editor.removeSecondaryCarets
            SS editor.selectAllOccurrences
            SB editor.selectCurrentBlock
            SL editor.selectCurrentLine
            SM editor.selectCurrentMethod
            SD editor.selectCurrentStatement
            SW editor.selectCurrentWord
            ST editor.selectEnclosingType
            SF editor.selectMatchingBrackets
            SN editor.selectNextOccurrence
            SG editor.shrinkSelection
            SH editor.smartEnd
            SI editor.smartHome
            SJ editor.toggleColumnSelection
            SK selectAll
            # D: Structural movement
            DM editor.moveCodeBlockDown
            DC editor.moveCodeBlockUp
            DE editor.moveElementLeft
            DR editor.moveElementRight
            DS editor.moveStatementDown
            DU editor.moveStatementUp
            # E: Editing and search
            EC comment
            EA copy
            EB cut
            ED editor.changeCase
            ES editor.convertSpacesToTabs
            ET editor.convertTabsToSpaces
            EN editor.deleteNextWord
            EP editor.deletePreviousWord
            EL editor.lowerCase
            EE editor.toggleBlockComment
            EF editor.transposeCharacters
            EW editor.trimTrailingWhitespace
            EU editor.upperCase
            EG find
            EH findNext
            EI findPrevious
            EJ paste
            ER redo
            EK replace
            EM undo
            # G: Java generation
            GC java.completeStatement
            GG java.generate
            GA java.generateConstructor
            GE java.generateEquals
            GS java.generateGetterSetter
            GB java.generateGetters
            GH java.generateHashCode
            GJ java.generateJavadoc
            GD java.generateSetters
            GT java.generateToString
            GI java.implementMethods
            GO java.overrideMethods
            GF java.surroundIf
            GK java.surroundTryFinally
            # H: Hierarchy and usages
            HC navigation.callHierarchy
            HM navigation.calledMethods
            HF navigation.findReferences
            HG navigation.goToImplementation
            HB navigation.matchingBrace
            HN navigation.nextMethod
            HP navigation.previousMethod
            HR navigation.relatedTests
            HT navigation.typeHierarchy
            # N: Navigation
            NB back
            ND declaration
            NF forward
            NG goto
            NT navigation.goToFile
            NS navigation.goToSymbol
            NA navigation.goToTest
            NC navigation.goToType
            NM navigation.mavenModule
            NN navigation.nextEditLocation
            NE navigation.nextEditorTab
            NH navigation.nextError
            NW navigation.nextWarning
            NP navigation.previousEditLocation
            NI navigation.previousEditorTab
            NJ navigation.previousError
            NK navigation.previousWarning
            NL navigation.projectDependency
            NR navigation.recentFiles
            NO navigation.recentLocations
            NQ navigation.searchEverywhere
            NU navigation.testFailure
            # O: Appearance
            OF fontDown
            OU fontUp
            OA fullscreen
            OS showPressedShortcuts
            OC theme.classic
            OD theme.dark
            OL theme.light
            OB theme.load
            OM theme.more
            OZ zoom
            # I: Formatting
            ID format.document
            IO format.onSave
            IS format.selection
            IA format.style.aosp
            IB format.style.aws
            IC format.style.classic80
            IE format.style.eclipse
            IG format.style.google
            II format.style.intellij
            IJ format.style.javafmt
            IW format.style.wide120
            IF organizeImports
            # M: Maven
            MC maven.build.clean
            MA maven.build.compile
            MB maven.build.custom
            MD maven.build.dependency:tree
            MH maven.build.help:effective-pom
            MI maven.build.install
            MP maven.build.package
            MT maven.build.test
            MV maven.build.validate
            ME maven.build.verify
            MR maven.refresh
            MS maven.stop
            # P: Project
            PD project.dependencies
            PT project.dependencyTree
            PE project.effectivePom
            PA project.explorer
            PF project.findFile
            PM project.findModule
            PJ project.javadoc
            PO project.open
            PP project.parentPom
            PB project.pom.add-dependency
            PC project.pom.add-module
            PR project.pom.remove-dependency
            PU project.pom.update-dependency-version
            PG project.pomDeclaration
            PH project.processors
            PI project.profiles
            PK project.reload
            PS project.settings
            PL project.sources
            PN project.structure
            # X: Extract and inline
            XE refactor.extract-constant
            XA refactor.extract-field
            XB refactor.extract-interface
            XC refactor.extract-method
            XD refactor.extract-parameter
            XF refactor.extract-variable
            XI refactor.inline
            XG refactor.inline-method
            XH refactor.inline-variable
            # Q: Quick fixes and conversions
            QA refactor.add-import
            QB refactor.anonymous-to-lambda
            QC refactor.constructor-to-factory
            QD refactor.context-actions
            QE refactor.create-constructor
            QF refactor.create-field
            QG refactor.create-local
            QH refactor.create-method
            QI refactor.fix-type-mismatch
            QL refactor.lambda-to-method-reference
            QM refactor.method-reference-to-lambda
            QR refactor.remove-import
            QJ refactor.remove-unreachable
            QS refactor.switch-to-expression
            QT refactor.type-to-var
            QV refactor.var-to-type
            # R: Refactoring
            RC refactor.change-signature
            RE refactor.encapsulate-field
            RI refactor.implement-methods
            RA refactor.introduce-interface
            RM refactor.move
            RP refactor.pull-up
            RB refactor.push-down
            RR refactor.rename
            RS refactor.safe-delete
            RT refactor.this
            # T: Testing and coverage
            TC cherry.cancel
            TA cherry.configure
            TE cherry.execution
            TB cherry.report
            TD cherry.results
            TR cherry.run
            TI editor.inspectLineIntelligence
            TF tests.all
            TG tests.auto
            TH tests.class
            TJ tests.current
            TK tests.failed
            TN tests.nextFailure
            TS tests.onSave
            TP tests.previousFailure
            TL tests.results
            TM tests.stop
            # V: Panels
            VF focus
            VB view.coverageBackgrounds
            VC view.focusConsole
            VD view.focusDiagnostics
            VE view.focusEditor
            VP view.focusExplorer
            VJ view.focusJShell
            VN view.focusNextPanel
            VA view.focusPreviousPanel
            VT view.focusTestResults
            VM view.maximizeEditor
            VR view.resizeOutput
            VL view.restoreLayout
            VG view.toggleConsole
            VH view.toggleExplorer
            VI view.toggleTestPanel
            # W: Explorer
            WC explorer.collapse
            WM explorer.contextMenu
            WD explorer.delete
            WE explorer.expand
            WN explorer.newClass
            WF explorer.newFile
            WA explorer.newFolder
            WP explorer.newPackage
            WO explorer.open
            """;
}
