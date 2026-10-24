package dev.turboj.refactorj.runtime;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ClassicIT {
    static RefactorJRuntime runtime;
    @TempDir Path root;
    @BeforeAll static void start()throws Exception{runtime=new RefactorJRuntime();}
    @AfterAll static void stop(){if(runtime!=null)runtime.close();}
    @Test void localRenameAvoidsShadowedField()throws Exception{var p=plan("class C { int n; int f(int n) { return n + this.n; } }","int n)",4,0,l->new Rename(l,"value"));String out=output(p);assertTrue(out.contains("int value)"));assertTrue(out.contains("value + this.n"));}
    @Test void renameOverloadOnly()throws Exception{var p=plan("class C { int f(int n) { return n; } String f(String n) { return n; } int g(){return f(1);} }","f(int",0,1,l->new Rename(l,"number"));String out=output(p);assertTrue(out.contains("number(1)"));assertTrue(out.contains("String f(String"));}
    @Test void extractMethod()throws Exception{var p=plan("class C { int f(int x) { int n = x + 1; return n; } }","int n = x + 1;",0,14,l->new ExtractMethod(l,"calculate"));assertTrue(output(p).contains("calculate(x)"));}
    @Test void extractVariable()throws Exception{var p=plan("class C { int f(int x) { return x + 1; } }","x + 1",0,5,l->new ExtractVariable(l,"value",false,false));assertTrue(output(p).contains("int value = x + 1;"));}
    @Test void extractConstant()throws Exception{var p=plan("class C { int f() { return 42; } }","42",0,2,l->new ExtractConstant(l,"ANSWER",Visibility.PRIVATE,false));assertTrue(output(p).contains("ANSWER = 42"));}
    @Test void extractField()throws Exception{var p=plan("class C { int f() { int value = 42; return value; } }","value =",0,5,l->new ExtractField(l,"answer",Visibility.PRIVATE,FieldInitialization.CURRENT_METHOD));assertTrue(output(p).contains("int answer"));}
    @Test void extractParameter()throws Exception{var p=plan("class C { int f(int x) { return x + 1; } int g(){return f(2);} }","1;",0,1,l->new ExtractParameter(l,"value"));assertTrue(output(p).contains("int value"));}
    @Test void inlineVariable()throws Exception{var p=plan("class C { int f() { int value = 42; return value; } }","value =",0,5,InlineVariable::new);assertTrue(output(p).contains("return 42;"));}
    @Test void inlineMethod()throws Exception{var p=plan("class C { private int f(int x) { return x + 1; } int g(){return f(2);} }","f(2)",0,1,l->new InlineMethod(l,false,false));assertFalse(output(p).contains("return f(2)"));}
    @Test void changeSignature()throws Exception{var p=plan("class C { int f(int x,int y){return x+y;} int g(){return f(1,2);} }","f(int",0,1,l->new ChangeSignature(l,"sum","int",Visibility.PACKAGE,List.of(new Parameter(1,"int","b",""),new Parameter(0,"int","a",""))));assertTrue(output(p).replace(" ","").contains("sum(2,1)"),output(p));}
    @Test void encapsulateField()throws Exception{var p=plan("class C { public int value; int f(){return value;} }","value;",0,5,l->new EncapsulateField(l,"getValue","setValue",Visibility.PUBLIC));assertTrue(output(p).contains("getValue()"));}
    @Test void constructorFactory()throws Exception{var p=plan("class C { C(int n) {} static C f(){ return new C(1); } }","C(int",0,1,l->new ConstructorToFactory(l,"create",true));assertTrue(output(p).contains("create(1)"));}
    @Test void safeDeleteUnusedMember()throws Exception{var p=plan("class C { private int unused(){return 1;} }","unused",0,6,SafeDelete::new);assertFalse(output(p).contains("unused"));}
    @Test void safeDeleteRejectsReference()throws Exception{Plan p=raw("class C { int used(){return 1;} int f(){return used();} }","used",0,4,SafeDelete::new);assertFalse(p.valid());assertTrue(p.edits().isEmpty());}
    @Test void extractInterfaceCreatesRealSource()throws Exception{var p=plan("class C { public int value(){return 1;} }","C {",0,1,l->new ExtractInterface(l,"Value",List.of(),false));assertEquals(1,p.fileOperations().stream().filter(f->f.kind()==FileOperationKind.CREATE).count());assertTrue(output(p).contains("implements Value"));}
    @Test void introduceInterface()throws Exception{var p=plan("class C { public int value(){return 1;} }","C {",0,1,l->new IntroduceInterface(l,"Value",List.of()));assertTrue(output(p).contains("implements Value"));}
    @Test void crossFileRenameUsesCurrentBuffers()throws Exception{
        var sources=Map.of("A.java","class A { int value(int n){return n;} }","B.java","class B { int f(){return new A().value(2);} }");
        Plan p=multi(sources,"A.java","value",l->new Rename(l,"calculate"));assertEquals(2,p.edits().size());assertTrue(changed(p,"B.java").contains("calculate(2)"));
    }
    @Test void pullUpMember()throws Exception{var p=multi(Map.of("A.java","class A {}","B.java","class B extends A { int value(){return 1;} }"),"B.java","value",l->new PullUp(l,"A",List.of()));assertTrue(changed(p,"A.java").contains("value()"));assertFalse(changed(p,"B.java").contains("value()"));}
    @Test void pushDownMember()throws Exception{var p=multi(Map.of("A.java","class A { int value(){return 1;} }","B.java","class B extends A {}"),"A.java","value",l->new PushDown(l,List.of(),false));assertTrue(changed(p,"B.java").contains("value()"));assertFalse(changed(p,"A.java").contains("value()"));}
    @Test void moveStaticMethod()throws Exception{var p=multi(Map.of("A.java","class A { static int value(){return 1;} }","B.java","class B { int f(){return A.value();} }"),"A.java","value",l->new Move(l,MoveKind.STATIC_MEMBER,"B",""));assertTrue(changed(p,"B.java").contains("static int value()"));}
    @Test void moveTopLevelType()throws Exception{var p=multi(Map.of("A.java","public class A { public int value(){return 1;} }","B.java","class B { A value; }"),"A.java","A {",l->new Move(l,MoveKind.TYPE,"destination",""));assertTrue(p.fileOperations().stream().anyMatch(f->f.kind()==FileOperationKind.MOVE&&f.destination().endsWith("destination/A.java")));assertTrue(changed(p,"B.java").contains("import destination.A"));}
    @Test void renameTypeMovesItsFile()throws Exception{var p=multi(Map.of("A.java","public class A {}","B.java","class B { A value; }"),"A.java","A {",l->new Rename(l,"Renamed"));assertTrue(p.fileOperations().stream().anyMatch(f->f.kind()==FileOperationKind.MOVE&&f.destination().endsWith("Renamed.java")));}
    @Test void unsavedReplacementReconcilesCodeSelection()throws Exception{
        Path file=root.resolve("A.java");var before=new WorkspaceSnapshot(root,1,List.of(new SourceSnapshot(file,"class A {}",1,false)),List.of(root),List.of());try(var engine=runtime.openWorkspace(before)){String text="class A { int run(){ int total = 1; return total; } }";engine.workspace().synchronize(new WorkspaceSnapshot(root,2,List.of(new SourceSnapshot(file,text,2,true)),List.of(root),List.of()),Cancellation.NONE);var p=engine.prepare(new Rename(new Location(file,text.indexOf("total")),"sum"),Cancellation.NONE);assertTrue(p.valid(),p.diagnostics().toString());assertTrue(output(p).contains("return sum"));}
    }
    private Plan multi(Map<String,String> texts,String selectedFile,String needle,Function<Location,Request> request)throws Exception{var sources=texts.entrySet().stream().map(e->new SourceSnapshot(root.resolve(e.getKey()),e.getValue(),1,true)).toList();var snapshot=new WorkspaceSnapshot(root,1,sources,List.of(root),List.of());try(var engine=runtime.openWorkspace(snapshot)){var p=engine.prepare(request.apply(new Location(root.resolve(selectedFile),texts.get(selectedFile).indexOf(needle))),Cancellation.NONE);assertTrue(p.valid(),p.diagnostics().toString());RefactoringAssertions.roundTrip(p);assertEquals(snapshot,engine.workspace().snapshot());return p;}}
    private String changed(Plan plan,String name){Path file=root.resolve(name);String text=plan.before().source(file).text();for(var e:plan.edits())if(e.file().equals(file))text=TextEdit.apply(text,e.edits());return text;}
    private Plan plan(String source,String needle,int shift,int length,Function<Location,Request> request)throws Exception{var p=raw(source,needle,shift,length,request);assertTrue(p.valid(),()->p.diagnostics().toString());RefactoringAssertions.roundTrip(p);return p;}
    private Plan raw(String source,String needle,int shift,int length,Function<Location,Request> request)throws Exception{Path file=root.resolve("C.java");var snapshot=new WorkspaceSnapshot(root,1,List.of(new SourceSnapshot(file,source,1,true)),List.of(root),List.of());try(var engine=runtime.openWorkspace(snapshot)){int start=source.indexOf(needle)+shift;Plan plan=engine.prepare(request.apply(new Location(file,start,start+length)),Cancellation.NONE);assertEquals(snapshot,engine.workspace().snapshot());assertFalse(Files.exists(file));return plan;}}
    private String output(Plan plan){String source=plan.before().sources().getFirst().text();for(var changes:plan.edits())if(changes.file().equals(plan.before().sources().getFirst().file()))source=TextEdit.apply(source,changes.edits());return source;}
}
