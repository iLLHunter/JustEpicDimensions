package justepicdimensions.monitor;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Explicitly opened client-local view. No filesystem paths, server listener or browser administration API. */
public final class JEDLocalDashboard implements AutoCloseable {
 private final HttpServer http;private final ExecutorService threads;private final Function<JsonObject,CompletableFuture<JsonObject>> relay;private final String token=UUID.randomUUID().toString()+UUID.randomUUID();private final String origin;private volatile boolean closed;
 public JEDLocalDashboard(Function<JsonObject,CompletableFuture<JsonObject>> relay)throws IOException{this.relay=relay;http=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);origin="http://127.0.0.1:"+http.getAddress().getPort();threads=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),Thread.ofPlatform().daemon(true).name("JED local dashboard").factory());http.setExecutor(threads);http.createContext("/",this::handle);http.start();}
 public URI uri(){return URI.create(origin+"/#"+token);}
 private void handle(HttpExchange x)throws IOException{try{
  var h=x.getResponseHeaders();h.set("Cache-Control","no-store");h.set("Referrer-Policy","no-referrer");h.set("X-Frame-Options","DENY");h.set("X-Content-Type-Options","nosniff");h.set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
  if(closed){respond(x,503,"text/plain","Disconnected".getBytes(StandardCharsets.UTF_8));return;}
  String host=x.getRequestHeaders().getFirst("Host"),supplied=x.getRequestHeaders().getFirst("Origin");if(!origin.substring(7).equals(host)||supplied!=null&&!supplied.equals(origin)){respond(x,403,"text/plain",new byte[0]);return;}
  String path=x.getRequestURI().getPath();if(path.equals("/api/view")){String auth=x.getRequestHeaders().getFirst("Authorization");if(auth==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),auth.getBytes(StandardCharsets.UTF_8))){respond(x,401,"text/plain",new byte[0]);return;}if(!x.getRequestMethod().equals("POST")){respond(x,405,"text/plain",new byte[0]);return;}byte[] body=x.getRequestBody().readNBytes(2049);if(body.length>2048){respond(x,413,"text/plain",new byte[0]);return;}
   try{var request=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();var reply=relay.apply(request).get(12,TimeUnit.SECONDS);respond(x,200,"application/json; charset=utf-8",reply.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception error){var j=new JsonObject();j.addProperty("error",error instanceof TimeoutException?"Minecraft connection did not respond; data is stale":String.valueOf(error.getMessage()));respond(x,503,"application/json; charset=utf-8",j.toString().getBytes(StandardCharsets.UTF_8));}return;
  }
  if(!x.getRequestMethod().equals("GET")||x.getRequestURI().getRawQuery()!=null){respond(x,405,"text/plain",new byte[0]);return;}String resource=switch(path){case "/"->"index.html";case "/app.js"->"app.js";case "/style.css"->"style.css";default->null;};if(resource==null){respond(x,404,"text/plain",new byte[0]);return;}try(var in=JEDLocalDashboard.class.getResourceAsStream("/assets/justepicdimensions/monitoring/"+resource)){if(in==null){respond(x,404,"text/plain",new byte[0]);return;}respond(x,200,resource.endsWith("html")?"text/html; charset=utf-8":resource.endsWith("js")?"text/javascript; charset=utf-8":"text/css; charset=utf-8",in.readAllBytes());}
 }finally{x.close();}}
 private static void respond(HttpExchange x,int code,String type,byte[] body)throws IOException{x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(code,body.length==0?-1:body.length);if(body.length>0)x.getResponseBody().write(body);}
 public void close(){closed=true;http.stop(0);threads.shutdownNow();}
}
