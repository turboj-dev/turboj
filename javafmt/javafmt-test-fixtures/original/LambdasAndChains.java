import java.util.List;
import java.util.function.Function;
public class LambdasAndChains{
void print(List<String> values){values.stream().filter(value->value.length()>3).map(String::strip).forEach(System.out::println);}
Function<String,Integer> parser=value->{return Integer.parseInt(value);};
Runnable task=new Runnable(){public void run(){System.out.println("done");}};
}
