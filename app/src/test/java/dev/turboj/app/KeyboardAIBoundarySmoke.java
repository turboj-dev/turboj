package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.ai.*;
import dev.turboj.ai.local.LocalModelManager;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import com.sun.net.httpserver.*;
import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import javax.swing.*;

/** Native successful settings transactions, real cached Ollama pull, isolated HTTPS cloud protocols.
 * Cloud responses are explicit deterministic fixtures, never evidence of paid-provider inference. */
public final class KeyboardAIBoundarySmoke {
    static final String MODEL="qwen2.5-coder:0.5b-base", TOKEN="turboj-disposable-fixture-not-a-provider-token";
    static final List<String> endpoints=new ArrayList<>();
    static final Map<String,AtomicInteger> requests=new ConcurrentHashMap<>();
    static final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    static final AtomicBoolean failRequest=new AtomicBoolean();
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-ai-boundary"));
        work=Files.createTempDirectory("turboj-ai-boundary-").toRealPath();
        System.setProperty("turboj.config",work.resolve("preferences").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        HttpsServer server=server();String base="https://127.0.0.1:"+server.getAddress().getPort();
        Path source=Files.writeString(work.resolve("C.java"),"class C {}\n");settings=new Settings();
        settings.ai=settings.ai.withLocal(AIConfig.LocalBackend.OLLAMA,"http://127.0.0.1:11434",MODEL,AIConfig.LocalRuntime.EXTERNAL);
        // No download of an uninstalled model: this run exercises an explicit registry/cache refresh.
        var http=java.net.http.HttpClient.newHttpClient();String tags=http.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/tags")).build(),java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        check(tags.contains(MODEL),"required real model already installed");
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(source);return f;});
        try {
            await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source ready");
            action("ai.local");dialog();named("ai-local-model");replace(MODEL);
            named("ai-local-automatic");if(edt(()->((JCheckBox)focus()).isSelected()))key(KeyEvent.VK_SPACE,0);
            button("Apply");await(()->!OverlayHost.active(frame)&&settings.ai.mode()==AIConfig.Mode.LOCAL&&frame.localAIStatus().ready(),"local apply and real runtime ready");
            check(edt(()->frame.localAIStatus().ownership())==LocalModelManager.Ownership.EXTERNAL,"does not own external server");
            await(()->Settings.load().ai.mode()==AIConfig.Mode.LOCAL&&Settings.load().ai.localModel().equals(MODEL),"local persisted");
            pass("ai.local","real command opens Local form; Apply persists selected model; existing Ollama becomes READY without taking process ownership");
            var prior=edt(()->frame.localAIStatus());action("ai.download");dialog();button("OK");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);boolean downloadSeen=false;
            while(System.nanoTime()<until){var status=edt(()->frame.localAIStatus());downloadSeen|=status.state()==LocalModelManager.State.DOWNLOADING_MODEL;
                if(downloadSeen&&status!=prior&&status.ready())break;
                if(downloadSeen&&status.state()==LocalModelManager.State.UNAVAILABLE)throw new AssertionError("real Ollama pull: "+status.toString());
                Thread.sleep(10);}
            check(downloadSeen&&edt(()->frame.localAIStatus().ready()),"explicit actual pull returns READY");
            pass("ai.download","keyboard confirms actual Ollama /api/pull for already installed model; observes DOWNLOADING_MODEL then READY; cached blobs, no new model or owned server");
            for(AIConfig.CloudProvider provider:AIConfig.CloudProvider.values()) {
                String endpoint=base+"/"+provider.name().toLowerCase(Locale.ROOT)+"/v1";endpoints.add(endpoint);
                action("ai.cloud");dialog();named("ai-cloud-provider");choose(provider.ordinal());
                named("ai-cloud-endpoint");replace(endpoint);named("ai-cloud-model");replace("fixture-model");
                named("ai-cloud-automatic");if(edt(()->((JCheckBox)focus()).isSelected()))key(KeyEvent.VK_SPACE,0);
                // An invalid endpoint must retain the form, then a keyboard correction must succeed.
                named("ai-cloud-endpoint");replace(endpoint.replace("https:","http:"));named("ai-cloud-consent");key(KeyEvent.VK_SPACE,0);button("Apply");
                await(()->find(top(),JLabel.class,"ai-settings-status").getText().contains("HTTPS"),"invalid cloud endpoint stays in form");
                named("ai-cloud-endpoint");replace(endpoint);named("ai-cloud-consent");if(!edt(()->((JCheckBox)focus()).isSelected()))key(KeyEvent.VK_SPACE,0);
                named("ai-api-key");replace(TOKEN);button("Apply");await(()->OverlayHost.dialogs(frame).size()==2,"cloud consent confirmation");button("OK");
                await(()->!OverlayHost.active(frame)&&settings.ai.mode()==AIConfig.Mode.CLOUD&&settings.ai.cloudProvider()==provider,"cloud apply");
                await(()->Settings.load().ai.cloudEndpoint().equals(endpoint),"cloud persisted");
                check(new MacKeychainCredentialStore().read(endpoint).equals(TOKEN),"disposable credential stored in actual Keychain");
                int before=edt(()->frame.statusMessages().split("first text after",-1).length);
                action("ai.test");await(()->frame.statusMessages().split("first text after",-1).length>before,"HTTPS protocol fixture probe");
                check(requests.getOrDefault(provider.name().toLowerCase(Locale.ROOT),new AtomicInteger()).get()>0,"actual configured HTTPS provider request");
                check(serverFailure.get()==null,"fixture authentication/protocol assertions");
                check(edt(()->frame.statusMessages().contains("from cloud:"+provider.name().toLowerCase(Locale.ROOT)+"/fixture-model")),"cloud result is not a local fallback");
                check(edt(()->frame.editor().getText()).equals("class C {}\n"),"test completion leaves source unchanged");
                pass("ai.cloud","keyboard "+provider+" configuration: invalid HTTP rejected, HTTPS corrected, consent approved, real Keychain credential and preferences saved; actual HTTPS request to deterministic loopback protocol fixture succeeds (not live vendor inference)");
            }
            // The command deliberately permits cloud→local fallback. Verify its real route,
            // then use the settings probe (single configured route) to assert the provider error.
            failRequest.set(true);action("ai.test");
            await(()->frame.statusMessages().contains("from local:"+MODEL),"explicit local fallback route after cloud rejection");
            action("ai.settings");dialog();button("Test completion");
            await(()->find(top(),JLabel.class,"ai-settings-status").getText().contains("rejected the API key (HTTP 401)"),"single-provider rejection visible");
            button("Cancel");await(()->!OverlayHost.active(frame),"failed probe closes");
            pass("ai.test","three cloud TLS/auth/stream protocols use explicit fixtures; HTTP 401 triggers real local fallback with route shown; single-provider settings probe exposes 401 without false success");
            action("ai.disabled");await(()->settings.ai.mode()==AIConfig.Mode.DISABLED,"disable before cleanup");
        } finally {
            Files.writeString(output.resolve("effects.tsv"),report);
            if(frame!=null){Files.writeString(output.resolve("status.txt"),edt(()->frame.statusMessages()));edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
            server.stop(0);
            for(String endpoint:endpoints){
                var credentials=new MacKeychainCredentialStore();credentials.delete(endpoint);
                check(credentials.read(endpoint).isEmpty(),"disposable credential removed");
            }
            System.out.println("CLEANUP: owned HTTPS fixture stopped; disposable Keychain accounts removed; external Ollama left running");
        }
    }
    static void choose(int index)throws Exception {
        var combo=edt(()->(JComboBox<?>)focus());key(KeyEvent.VK_DOWN,KeyEvent.ALT_DOWN_MASK);await(combo::isPopupVisible,"provider choices");
        key(KeyEvent.VK_HOME,0);for(int i=0;i<index;i++)key(KeyEvent.VK_DOWN,0);key(KeyEvent.VK_ENTER,0);
    }
    static HttpsServer server()throws Exception {
        Path store=work.resolve("fixture.p12");
        var tool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=ip:127.0.0.1,dns:localhost","-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-password","-keypass","fixture-password","-noprompt").redirectErrorStream(true).start();
        tool.getInputStream().readAllBytes();check(tool.waitFor()==0,"fixture TLS certificate");
        // Only this test JVM trusts this isolated certificate. No macOS trust settings change.
        System.setProperty("javax.net.ssl.trustStore",store.toString());System.setProperty("javax.net.ssl.trustStorePassword","fixture-password");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,"fixture-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-password".toCharArray());
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,null);
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        server.createContext("/",exchange->{
            try {
                String path=exchange.getRequestURI().getPath(), provider=path.split("/")[1];
                String credential=exchange.getRequestHeaders().getFirst(provider.equals("anthropic")?"x-api-key":"Authorization");
                check((provider.equals("anthropic")?TOKEN:"Bearer "+TOKEN).equals(credential),"endpoint-bound fixture credential");
                String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                check(body.contains("fixture-model")&&body.contains("multiply"),"probe uses synthetic source and selected model");
                requests.computeIfAbsent(provider,k->new AtomicInteger()).incrementAndGet();
                if(failRequest.get()){byte[] error="{\"error\":{\"message\":\"fixture rejection\"}}".getBytes();exchange.sendResponseHeaders(401,error.length);exchange.getResponseBody().write(error);return;}
                String event=switch(provider){
                    case "openai" -> "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"a * b;\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\"}\n\n";
                    case "anthropic" -> "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"a * b;\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
                    case "xai" -> "data: {\"choices\":[{\"delta\":{\"content\":\"a * b;\"}}]}\n\ndata: [DONE]\n\n";
                    default -> throw new AssertionError("unexpected protocol");
                };
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(event.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }catch(Throwable failure){serverFailure.set(failure);}finally{exchange.close();}
        });server.start();return server;
    }
}
