import com.netflix.spectator.impl.PatternMatcher;

/** Confirms the worst-case pattern found so far and reports the exact timing. */
public final class RedosLive {
  static long time(String p, String v, long budgetMs) throws Exception {
    PatternMatcher m = PatternMatcher.compile(p);
    final long[] out = {-1};
    Thread t = new Thread(() -> {
      long t0 = System.nanoTime();
      m.matches(v);
      out[0] = (System.nanoTime() - t0) / 1_000_000;
    });
    t.setDaemon(true);
    t.start();
    t.join(budgetMs);
    return t.isAlive() ? -1 : out[0];
  }

  public static void main(String[] args) throws Exception {
    String p = "(a+)+(a+)+(a+)+(a+)+b";
    for (int n : new int[] {80, 100, 120, 160}) {
      long ms = time(p, "a".repeat(n), 90000);
      System.out.printf("subject=%-4d %s%n", n, ms < 0 ? ">90000 ms" : ms + " ms");
    }
    System.exit(0);
  }
}
