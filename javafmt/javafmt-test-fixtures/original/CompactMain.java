import module java.base;
void main(){var values=List.of(1,2,3);IO.println(values.stream().mapToInt(Integer::intValue).sum());}
