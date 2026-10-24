sealed interface Coordinate permits Position,Origin{}
record Position(int x,int y)implements Coordinate{}
final class Origin implements Coordinate{}
public class ModernTypes{
int read(Coordinate value){return switch(value){case Position(int x,int y)when x>0->x+y;case Position(int x,int y)->y;case Origin origin->0;};}
}
