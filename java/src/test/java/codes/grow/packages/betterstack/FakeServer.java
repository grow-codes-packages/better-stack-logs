package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/** Local HTTP server that records raw request bodies and replays recorded stub fixtures. */
final class FakeServer implements AutoCloseable {
  /** A recorded request. */
  record Req(
      String method, String path, String rawQuery, Map<String, List<String>> headers, byte[] body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().get(0);
        }
      }
      return null;
    }

    /** Body after gzip decoding (when {@code Content-Encoding: gzip}). */
    byte[] decoded() {
      if (!"gzip".equals(header("Content-Encoding"))) {
        return body;
      }
      try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
        return in.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    String text() {
      return new String(decoded(), StandardCharsets.UTF_8);
    }

    Map<String, String> params() {
      Map<String, String> out = new LinkedHashMap<>();
      if (rawQuery == null) {
        return out;
      }
      for (String kv : rawQuery.split("&")) {
        int eq = kv.indexOf('=');
        out.put(
            URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
      }
      return out;
    }
  }

  /** A scripted response. */
  @FunctionalInterface
  interface Responder {
    void respond(HttpExchange ex, Req req) throws IOException;
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final ConcurrentLinkedQueue<Responder> queue = new ConcurrentLinkedQueue<>();
  private final List<Req> requests = new CopyOnWriteArrayList<>();
  private volatile Responder fallback = (ex, r) -> send(ex, 202, "", null);

  private FakeServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  static FakeServer start() {
    try {
      return new FakeServer();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void handle(HttpExchange ex) throws IOException {
    try {
      Req r =
          new Req(
              ex.getRequestMethod(),
              ex.getRequestURI().getPath(),
              ex.getRequestURI().getRawQuery(),
              Map.copyOf(ex.getRequestHeaders()),
              ex.getRequestBody().readAllBytes());
      requests.add(r);
      Responder next = queue.poll();
      (next != null ? next : fallback).respond(ex, r);
    } catch (IOException | RuntimeException e) {
      // client went away
    } finally {
      ex.close();
    }
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  FakeServer enqueue(Responder r) {
    queue.add(r);
    return this;
  }

  FakeServer enqueue(int status, String body, String contentType, String... headers) {
    return enqueue(
        (ex, r) -> {
          for (int i = 0; i + 1 < headers.length; i += 2) {
            ex.getResponseHeaders().add(headers[i], headers[i + 1]);
          }
          send(ex, status, body, contentType);
        });
  }

  /** Replays the recorded response of {@code src/test/resources/fixtures/<name>.json}. */
  FakeServer enqueueFixture(String name) {
    return enqueue(fixtureResponder(name));
  }

  FakeServer fallback(Responder r) {
    fallback = r;
    return this;
  }

  List<Req> requests() {
    return new ArrayList<>(requests);
  }

  Req last() {
    return requests.get(requests.size() - 1);
  }

  static JsonNode fixture(String name) {
    try (InputStream in = FakeServer.class.getResourceAsStream("/fixtures/" + name + ".json")) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return Json.tree(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Body text of a recorded response (objects as one JSON line, arrays as a JSON array). */
  static String fixtureBody(String name) {
    JsonNode body = fixture(name).path("response").path("body");
    if (body.isNull() || body.isMissingNode()) {
      return "";
    }
    return body.isTextual() ? body.asText() : body.toString() + "\n";
  }

  static Responder fixtureResponder(String name) {
    JsonNode res = fixture(name).path("response");
    return (ex, r) -> {
      String contentType = null;
      for (Map.Entry<String, JsonNode> h : res.path("headers").properties()) {
        String k = h.getKey().toLowerCase();
        if (k.equals("content-type")) {
          contentType = h.getValue().asText();
        } else if (k.startsWith("x-")) {
          ex.getResponseHeaders().add(h.getKey(), h.getValue().asText());
        }
      }
      send(ex, res.path("status").asInt(), fixtureBody(name), contentType);
    };
  }

  static void send(HttpExchange ex, int status, String body, String contentType)
      throws IOException {
    sendBytes(ex, status, body.getBytes(StandardCharsets.UTF_8), contentType);
  }

  static void sendBytes(HttpExchange ex, int status, byte[] b, String contentType)
      throws IOException {
    if (contentType != null) {
      ex.getResponseHeaders().add("Content-Type", contentType);
    }
    ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
    if (b.length > 0) {
      try (OutputStream os = ex.getResponseBody()) {
        os.write(b);
      }
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
