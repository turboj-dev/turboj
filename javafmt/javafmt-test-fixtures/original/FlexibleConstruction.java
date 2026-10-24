class ParentValue{ParentValue(int value){}}
public class FlexibleConstruction extends ParentValue{
FlexibleConstruction(int value){int adjusted=value+1;if(adjusted<0)throw new IllegalArgumentException();super(adjusted);}
}
