import java.nio.file.*;
import java.util.*;
import com.cherrytest.cli.adapters.Adapters;
import com.cherrytest.core.IntegrationRecords;
public class ReportCounts {
 public static void main(String[] args) {
  Path report=Path.of(args[0]);
  var adapter=Adapters.readRows(report); var http=IntegrationRecords.readAllRecords(report);
  var adapterNames=new TreeSet<String>();adapter.forEach(r->adapterNames.add(r.path("full_name").asString()));
  var httpNames=new TreeSet<String>();http.forEach(r->httpNames.add(r.path("full_name").asString()));
  var extra=new TreeSet<>(httpNames);extra.removeAll(adapterNames);
  var overlap=new TreeSet<>(httpNames);overlap.retainAll(adapterNames);
  System.out.println("Adapter test rows="+adapter.size()+", unique report names="+adapterNames.size());
  System.out.println("HTTP observations="+http.size()+", unique check names="+httpNames.size()+", shared="+overlap.size()+", additional="+extra.size());
  extra.forEach(System.out::println);
 }
}
