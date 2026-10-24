import java.util.List;
import java.util.Map;
public class GenericOperators<T extends Number & Comparable<T>>{
Map<String,List<? extends T>> values;
int shifts(int a,int b){return (a>>2)+(b>>>1)+(a<<3);}
boolean comparisons(int a,int b){return a<b&&b>=0||a==b;}
<R> R identity(R value){return value;}
void invoke(){String value=this.<String>identity("x");int n=1;n++;--n;}
}
