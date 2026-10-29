import com.netflix.spectator.atlas.impl.Parser;

/**
 * Real-world path: an Atlas query expression with a :re operator value is compiled by
 * com.netflix.spectator.atlas.impl.Query.Regex via PatternMatcher.compile("^" + value).
 */
public final class AtlasPoC {
  public static void main(String[] args) {
    String[] exprs = {
      "app.name,foo,:re",
      "app.name,foo|{2},:re",
      "app.name,{2},:re",
      "app.name,foo|{2},:reic",
      "app.name,foo,:contains"
    };
    for (String e : exprs) {
      try {
        Object q = Parser.parseQuery(e);
        System.out.printf("OK      %-28s -> %s%n", e, q);
      } catch (Throwable t) {
        System.out.printf("%-28s %s: %s%n", e, t.getClass().getName(),
            String.valueOf(t.getMessage()).split("\n")[0]);
        for (StackTraceElement s : t.getStackTrace()) {
          if (s.getClassName().startsWith("com.netflix")) {
            System.out.println("           at " + s);
          }
        }
      }
    }
  }
}
