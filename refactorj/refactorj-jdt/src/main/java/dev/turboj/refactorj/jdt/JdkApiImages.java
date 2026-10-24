package dev.turboj.refactorj.jdt;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipFile;

/**
 * Read-only Java API signatures for the Java model. JDT's code-assist Java model reads JRT
 * classes even with COMPILER_RELEASE enabled; exposing the JDK's own ct.sym signatures avoids
 * suggesting or binding APIs newer than --release. These private jars are never executable or
 * distributed, and do not replace the compiler's own platform implementation.
 */
final class JdkApiImages implements AutoCloseable {
    private final Map<String,List<Path>> cache=new HashMap<>();
    private Path directory;
    List<Path> libraries(Path home,String release)throws IOException {
        home=home.toAbsolutePath().normalize();
        if(release.isBlank()||release.equals(feature(home)))return List.of(home.resolve("lib/jrt-fs.jar"));
        String normalized=release.startsWith("1.")?release.substring(2):release;
        int version=Integer.parseInt(normalized);String code=Integer.toString(version,36).toUpperCase(Locale.ROOT);
        if(code.length()!=1)throw new IOException("Unsupported JDK signature release: "+release);
        Path symbols=home.resolve("lib/ct.sym");if(!Files.isRegularFile(symbols))throw new IOException("Selected JDK lacks ct.sym API signatures for --release "+release+": "+home);
        String key=home+"|"+release+"|"+Files.getLastModifiedTime(symbols);if(cache.containsKey(key))return cache.get(key);
        if(directory==null)directory=Files.createTempDirectory("refactorj-jdk-api-");Path image=Files.createTempDirectory(directory,"release-"+normalized+"-");
        Map<String,JarOutputStream> jars=new TreeMap<>();Map<String,Set<String>> names=new HashMap<>();
        try(ZipFile archive=new ZipFile(symbols.toFile())) {
            for(var entry:archive.stream().filter(e->!e.isDirectory()&&e.getName().endsWith(".sig")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
                String[] parts=entry.getName().split("/",3);if(parts.length!=3||!parts[0].contains(code))continue;
                String module=version<9?"java-platform":parts[1];String name=parts[2].substring(0,parts[2].length()-4)+".class";
                if(!names.computeIfAbsent(module,ignored->new HashSet<>()).add(name))throw new IOException("Ambiguous JDK signature: "+entry.getName());
                JarOutputStream jar=jars.get(module);if(jar==null){jar=new JarOutputStream(Files.newOutputStream(image.resolve(module+".jar")));jar.setLevel(0);jars.put(module,jar);}
                var output=new JarEntry(name);output.setTime(0);jar.putNextEntry(output);try(var input=archive.getInputStream(entry)){input.transferTo(jar);}jar.closeEntry();
            }
        }finally{for(var jar:jars.values())jar.close();}
        if(jars.isEmpty())throw new IOException("Selected JDK does not provide --release "+release+" API signatures");
        List<Path> result=jars.keySet().stream().map(name->image.resolve(name+".jar")).toList();cache.put(key,result);return result;
    }
    private static String feature(Path home)throws IOException{
        Properties release=new Properties();try(var input=Files.newInputStream(home.resolve("release"))){release.load(input);}String version=release.getProperty("JAVA_VERSION","").replace("\"","");if(version.startsWith("1."))version=version.substring(2);return version.split("[.\\-+]")[0];
    }
    @Override public void close(){if(directory!=null)try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}catch(IOException ignored){}cache.clear();}
}
