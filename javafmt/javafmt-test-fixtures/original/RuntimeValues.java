import java.util.List;
public class RuntimeValues{
public static void main(String[] args){
String block="""
    alpha
      beta\s
    """;
String escaped="A\tB\n😀";char quote='\'';
List<List<String>> nested=List.of(List.of("one","two"));
int a=37,b=3;int shifted=(a>>b)+(a>>>b)+(b<<2);
java.util.function.IntUnaryOperator twice=x->x*2;
System.out.println(block.replace("\n","|"));
System.out.println(escaped.replace("\n","|"));
System.out.println(quote+":"+nested.get(0).size()+":"+shifted+":"+twice.applyAsInt(9));
}
}
