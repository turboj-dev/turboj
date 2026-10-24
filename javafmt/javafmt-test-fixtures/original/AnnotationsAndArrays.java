import java.lang.annotation.*;
@Target({ElementType.TYPE_USE,ElementType.PARAMETER})@interface Tag{String value()default "";}
public class AnnotationsAndArrays{
@Deprecated(since="25",forRemoval=false)public <@Tag T> T identity(@Tag("input")T value){return value;}
int[][] grid={{1,2},{3,4}};
String[] names=new String[]{"a","b"};
void all(String... values){for(String value:values)System.out.println(value);}
}
