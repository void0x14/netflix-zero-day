import com.netflix.spectator.impl.PatternMatcher;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Minimal service that compiles a user supplied tag filter, the way a metrics/dashboard
 * backend would. Shows what a caller sees for a malformed pattern.
 */
public final class HttpPoC {
  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18099), 0);
    server.setExecutor(Executors.newFixedThreadPool(2));
    server.createContext("/tags", exchange -> {
      String q = exchange.getRequestURI().getRawQuery();
      String pattern = "";
      if (q != null && q.startsWith("pattern=")) {
        pattern = URLDecoder.decode(q.substring("pattern=".length()), StandardCharsets.UTF_8);
      }
      try {
        PatternMatcher matcher = PatternMatcher.compile(pattern);
        String body = "compiled=" + matcher.minLength() + ",matches(host-1)="
            + matcher.matches("host-1");
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.getResponseBody().close();
      } catch (IllegalArgumentException e) {
        // application-level validation: bad pattern -> 400
        byte[] out = ("invalid pattern: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(400, out.length);
        exchange.getResponseBody().write(out);
        exchange.getResponseBody().close();
      } catch (RuntimeException e) {
        // unhandled: framework turns this into a 500 and logs the stack trace
        byte[] out = ("server error: " + e.getClass().getName()).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(500, out.length);
        exchange.getResponseBody().write(out);
        exchange.getResponseBody().close();
        e.printStackTrace();
      }
    });
    server.start();
    System.out.println("listening on http://127.0.0.1:18099/tags?pattern=...");
  }
}
