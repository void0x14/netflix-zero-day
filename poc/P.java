import com.netflix.spectator.impl.PatternMatcher;

public final class P {
  public static void main(String[] args) {
    for (String p : new String[]{"{2}", "a?", "a*", "a+", "a{2}"}) {
      try {
        PatternMatcher.compile(p);
        System.out.println(p + " -> ok");
      } catch (Throwable t) {
        System.out.println(p + " -> " + t.getClass().getName() + ": " + t.getMessage());
      }
    }
  }
}
