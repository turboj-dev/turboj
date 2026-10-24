import java.io.StringReader;
public class ControlFlow{
void flow(int[] values)throws Exception{
for(int i=0;i<values.length;i++)if(values[i]<0)continue;
for(int value:values){if(value==0)break;else System.out.println(value);}
int index=0;while(index<values.length)index++;
do{index--;}while(index>0);
try(var reader=new StringReader("x")){reader.read();}catch(java.io.IOException|IllegalStateException failure){throw failure;}finally{index=0;}
synchronized(this){assert index==0:"unexpected";}
label:for(int a:values){if(a==2)break label;}
}
}
