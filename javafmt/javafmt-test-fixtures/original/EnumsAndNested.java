public class EnumsAndNested{
enum Operation{ADD{int apply(int a,int b){return a+b;}},MULTIPLY{int apply(int a,int b){return a*b;}};abstract int apply(int a,int b);}
static class Nested{private final int value;Nested(int value){this.value=value;}int value(){return value;}}
static{System.out.print("");}
{System.out.print("");}
}
