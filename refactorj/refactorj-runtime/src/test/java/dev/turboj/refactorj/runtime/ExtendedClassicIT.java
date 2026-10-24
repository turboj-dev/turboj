package dev.turboj.refactorj.runtime;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedClassicIT {
    static RefactorJRuntime runtime;
    @TempDir Path root;
    @BeforeAll static void start()throws Exception{runtime=new RefactorJRuntime();}
    @AfterAll static void stop(){if(runtime!=null)runtime.close();}
    @Test void renameRecordComponentAndAccessor()throws Exception{var p=plan(Map.of("Point.java","record Point(int x) { int doubled(){return x*2;} }","Use.java","class Use { int f(){return new Point(1).x();} }"),"Point.java","x)",l->new Rename(l,"value"));assertTrue(changed(p,"Use.java").contains(".value()"));}
    @Test void renameEnumConstant()throws Exception{var p=plan(Map.of("Color.java","enum Color { RED, BLUE }","Use.java","class Use { Color color=Color.RED; }"),"Color.java","RED",l->new Rename(l,"CRIMSON"));assertTrue(changed(p,"Use.java").contains("Color.CRIMSON"));}
    @Test void renameTypeParameter()throws Exception{var p=plan(Map.of("Box.java","class Box<T> { T value; T get(){return value;} }"),"Box.java","T>",l->new Rename(l,"Element"));assertTrue(changed(p,"Box.java").contains("Element value"));}
    @Test void renameAnnotationType()throws Exception{var p=plan(Map.of("Marker.java","@interface Marker {}","Use.java","@Marker class Use {}"),"Marker.java","Marker",l->new Rename(l,"Tagged"));assertTrue(changed(p,"Use.java").contains("@Tagged"));}
    @Test void renameOverrideFamily()throws Exception{var p=plan(Map.of("Base.java","interface Base { int value(); }","Impl.java","class Impl implements Base { public int value(){return 1;} }","Use.java","class Use { int f(Base base){return base.value();} }"),"Base.java","value",l->new Rename(l,"result"));assertTrue(changed(p,"Impl.java").contains("result()"));assertTrue(changed(p,"Use.java").contains("base.result()"));}
    @Test void renameRejectsScopeCollision()throws Exception{var p=raw(Map.of("A.java","class A { int f(int first,int second){return first+second;} }"),"A.java","first",l->new Rename(l,"second"));assertFalse(p.valid());}
    @Test void renamePackageMovesSourcesAndReferences()throws Exception{var p=plan(Map.of("oldpkg/A.java","package oldpkg; public class A {}","Use.java","class Use { oldpkg.A value; }"),"oldpkg/A.java","oldpkg",l->new Rename(l,"newpkg"));assertTrue(p.fileOperations().stream().anyMatch(f->f.destination()!=null&&f.destination().endsWith("newpkg/A.java")));assertTrue(changed(p,"Use.java").contains("newpkg.A"));}
    @Test void moveInstanceFieldToSuperclass()throws Exception{var p=plan(Map.of("Base.java","class Base {}","Child.java","class Child extends Base { protected int value=1; int f(){return value;} }"),"Child.java","value=",l->new Move(l,MoveKind.FIELD,"Base",""));assertTrue(changed(p,"Base.java").contains("int value"));assertFalse(changed(p,"Child.java").contains("int value"));}
    @Test void moveInstanceMethodToResolvedReceiver()throws Exception{var p=plan(Map.of("Source.java","class Source { int value(Target target){return target.number+1;} int call(Target target){return value(target);} }","Target.java","class Target { int number=2; }"),"Source.java","value(Target",l->new Move(l,MoveKind.INSTANCE_METHOD,"Target","target"));assertTrue(changed(p,"Target.java").contains("int value("));assertTrue(changed(p,"Source.java").contains("target.value("));}
    @Test void promoteInnerClassPreservesOuterAccess()throws Exception{var p=plan(Map.of("Outer.java","class Outer { int number=2; class Inner { int value(){return number;} } }"),"Outer.java","Inner",l->new Move(l,MoveKind.NESTED_TYPE,"","outer"));assertTrue(p.fileOperations().stream().anyMatch(f->f.kind()==FileOperationKind.CREATE&&f.path().endsWith("Inner.java")));}
    @Test void introduceInterfaceMigratesOnlySelectedUsage()throws Exception{
        var texts=Map.of("C.java","class C { public int value(){return 1;} }","Use.java","class Use { C selected=new C(); C retained=new C(); int f(){return selected.value()+retained.value();} }");
        var p=plan(texts,"C.java","C {",l->new IntroduceInterface(l,"Value",List.of(new Location(root.resolve("Use.java"),texts.get("Use.java").indexOf("C selected")))));String out=changed(p,"Use.java");assertTrue(out.contains("Value selected"));assertTrue(out.contains("C retained"));
    }
    @Test void movingFieldAcrossUnrelatedOwnershipIsRejected()throws Exception{var p=raw(Map.of("A.java","class A { int value=1; }","B.java","class B {}"),"A.java","value",l->new Move(l,MoveKind.FIELD,"B",""));assertFalse(p.valid());}
    @Test void selectedInterfaceCannotSilentlyChangeOverload()throws Exception{
        var texts=Map.of("C.java","class C { public int value(){return 1;} }","Use.java","class Use { String choose(C c){return \"C\";} String choose(Object c){return \"Object\";} String f(){ C selected=new C(); return choose(selected); } }");
        var p=raw(texts,"C.java","C {",l->new IntroduceInterface(l,"Value",List.of(new Location(root.resolve("Use.java"),texts.get("Use.java").indexOf("C selected")))));assertFalse(p.valid());assertTrue(p.diagnostics().stream().anyMatch(d->d.code().equals("changed.overload")));
    }
    @Test void signatureReorderingRejectsSideEffects()throws Exception{var p=raw(Map.of("A.java","class A { int count; int next(){return count++;} int sum(int a,int b){return a-b;} int f(){return sum(next(),next());} }"),"A.java","sum(int",l->new ChangeSignature(l,"sum","int",Visibility.PACKAGE,List.of(new Parameter(1,"int","b",""),new Parameter(0,"int","a",""))));assertFalse(p.valid());}
    @Test void inlineRejectsDuplicatedSideEffects()throws Exception{var p=raw(Map.of("A.java","class A { int count; int next(){return count++;} int f(){int value=next();return value+value;} }"),"A.java","value=",InlineVariable::new);assertFalse(p.valid());}
    @Test void fieldMoveRejectsConstructionOrderChange()throws Exception{var p=raw(Map.of("Base.java","class Base { Base(){read();} int read(){return 0;} }","Child.java","class Child extends Base { int value=1; int read(){return value;} }"),"Child.java","value=",l->new Move(l,MoveKind.FIELD,"Base",""));assertFalse(p.valid());}
    @Test void extractMethodAndSignatureChangePreserveObservedResult()throws Exception{
        String source="public class Program { static int sum(int a,int b){return a-b;} public static int answer(){return sum(7,2);} }";
        Plan p=plan(Map.of("Program.java",source),"Program.java","sum(int",l->new ChangeSignature(l,"difference","int",Visibility.PACKAGE,List.of(new Parameter(1,"int","right",""),new Parameter(0,"int","left",""))));
        assertEquals(execute(p,false),execute(p,true));assertEquals(5,execute(p,true));
    }
    private Object execute(Plan plan,boolean transformed)throws Exception{
        Plan candidate=transformed?plan:new Plan("original",plan.operation(),plan.before(),List.of(),List.of(),List.of(),List.of(),ValidationStatus.VALID,"original fixture");
        Path classes=compile(candidate);try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},ClassLoader.getPlatformClassLoader())){return loader.loadClass("Program").getMethod("answer").invoke(null);}
    }
    @Test void typeRenameUpdatesConstructorCallsInUnsavedBuffers()throws Exception{
        var p=plan(Map.of("Calculator.java","public class Calculator { public int sum(int a,int b){return a+b;} } // unsaved","Main.java","public class Main { int value=new Calculator().sum(1,2); }"),"Calculator.java","Calculator",l->new Rename(l,"Arithmetic"));assertTrue(changed(p,"Main.java").contains("new Arithmetic()"));
    }
    @Test void typeRenameAfterMethodRenameUndoRedoHasFreshCandidateBindings()throws Exception{
        Path main=root.resolve("Main.java"),calculator=root.resolve("Calculator.java");
        String mainText="public class Main { public int run() { return new Calculator().add(2, 3); } }\n";
        String calculatorText="public class Calculator { public int add(int a, int b) { return a + b; } }\n// unsaved\n";
        var snapshot=new WorkspaceSnapshot(root,1,List.of(new SourceSnapshot(main,mainText,1,true),new SourceSnapshot(calculator,calculatorText,1,true)),List.of(root),List.of());
        var memory=new dev.turboj.refactorj.transactions.InMemoryWorkspace(snapshot);var transactions=new dev.turboj.refactorj.transactions.WorkspaceTransactions(memory);
        try(var engine=runtime.openWorkspace(snapshot)){
            Plan first=engine.prepare(new Rename(new Location(main,mainText.indexOf("add(")),"sum"),Cancellation.NONE);assertTrue(first.valid(),first.diagnostics().toString());
            var applied=transactions.apply(first,Cancellation.NONE);assertNotNull(applied.transactionId());engine.workspace().synchronize(memory.snapshot(),Cancellation.NONE);
            transactions.undo(applied.transactionId(),Cancellation.NONE);engine.workspace().synchronize(memory.snapshot(),Cancellation.NONE);transactions.redo(applied.transactionId(),Cancellation.NONE);engine.workspace().synchronize(memory.snapshot(),Cancellation.NONE);
            Plan second=engine.prepare(new Rename(new Location(calculator,calculatorText.indexOf("Calculator")),"Arithmetic"),Cancellation.NONE);
            assertTrue(second.valid(),second.diagnostics()+" edits="+second.edits());compile(second);
        }
    }
    @Test void unicodeRenamePreservesCommentsAndLiterals()throws Exception{var p=plan(Map.of("Unicode.java","class Unicode { String text=\"名\"; int 𐐀(int 名){ /* 名 */ return 名; } int use(){return 𐐀(1);} }"),"Unicode.java","名)",l->new Rename(l,"value"));String out=changed(p,"Unicode.java");assertTrue(out.contains("/* 名 */"));assertTrue(out.contains("\"名\""));assertTrue(out.contains("return value"));}
    @Test void genericOverrideRenameUpdatesMethodReference()throws Exception{var p=plan(Map.of("Box.java","interface Box<T> { T read(); }","Strings.java","class Strings implements Box<String> { public String read(){return \"ok\";} }","Use.java","class Use { java.util.function.Supplier<String> source=new Strings()::read; }"),"Box.java","read",l->new Rename(l,"value"));assertTrue(changed(p,"Use.java").contains("::value"));assertTrue(changed(p,"Strings.java").contains("String value()"));}
    @Test void innerPromotionPreservesObservedOuterInstanceValue()throws Exception{
        var p=plan(Map.of("Outer.java","class Outer { int number=2; class Inner { int value(){return number;} } }","Program.java","public class Program { public static int answer(){return new Outer().new Inner().value();} }"),"Outer.java","Inner",l->new Move(l,MoveKind.NESTED_TYPE,"","outer"));assertEquals(execute(p,false),execute(p,true));assertEquals(2,execute(p,true));
    }
    @Test void interfaceMigrationPreservesObservedDispatch()throws Exception{
        var texts=Map.of("C.java","class C { public int value(){return 7;} }","Program.java","public class Program { public static int answer(){C chosen=new C();C retained=new C();return chosen.value()+retained.value();} }");
        var p=plan(texts,"C.java","C {",l->new IntroduceInterface(l,"Value",List.of(new Location(root.resolve("Program.java"),texts.get("Program.java").indexOf("C chosen")))));assertEquals(execute(p,false),execute(p,true));assertEquals(14,execute(p,true));
    }
    private Plan plan(Map<String,String> texts,String file,String needle,Function<Location,Request> request)throws Exception{Plan p=raw(texts,file,needle,request);assertTrue(p.valid(),p.diagnostics().toString());RefactoringAssertions.roundTrip(p);compile(p);return p;}
    private Plan raw(Map<String,String> texts,String file,String needle,Function<Location,Request> request)throws Exception{var snapshot=new WorkspaceSnapshot(root,1,texts.entrySet().stream().map(e->new SourceSnapshot(root.resolve(e.getKey()),e.getValue(),1,true)).toList(),List.of(root),List.of());try(var engine=runtime.openWorkspace(snapshot)){Plan p=engine.prepare(request.apply(new Location(root.resolve(file),texts.get(file).indexOf(needle))),Cancellation.NONE);assertEquals(snapshot,engine.workspace().snapshot());return p;}}
    private String changed(Plan p,String file){Path path=root.resolve(file);String text=p.before().source(path).text();for(var changes:p.edits())if(changes.file().equals(path))text=TextEdit.apply(text,changes.edits());return text;}
    private Path compile(Plan p)throws Exception{var sources=new LinkedHashMap<Path,String>();for(var s:p.before().sources())sources.put(s.file(),changed(p,root.relativize(s.file()).toString()));for(var op:p.fileOperations())switch(op.kind()){case CREATE->sources.put(op.path(),op.content());case DELETE->sources.remove(op.path());case MOVE->sources.put(op.destination(),sources.remove(op.path()));}Path destination=Files.createTempDirectory(root,"compile-");var args=new ArrayList<String>(List.of("--release","25","-d",destination.resolve("classes").toString()));for(var source:sources.entrySet()){Path path=destination.resolve(root.relativize(source.getKey()));Files.createDirectories(path.getParent());Files.writeString(path,source.getValue());args.add(path.toString());}var errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,errors,args.toArray(String[]::new)),errors.toString());return destination.resolve("classes");}
}
