package edu.jhu.hlt.fnparse.util;

import java.util.Random;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class RandomSpan {

  private final Random rand;

  public RandomSpan() {
    this(new Random(9001));
  }

  public RandomSpan(Random rand) {
    this.rand = rand;
  }

  public void setSeed(int seed) {
    rand.setSeed(seed);
  }

  public Span draw(int n) {
    int l = rand.nextInt(n);
    int r = rand.nextInt(n);
    if (l == r) {
      r = l + 1;
    } else if (l > r) {
      int t = l;
      l = r;
      r = t;
    }
    return Span.getSpan(l, r);
  }

  public Span draw(Iterable<Span> possible) {
    return DataUtil.reservoirSampleOne(possible, rand);
  }

  public static Span draw(int n, Random r) {
    return new RandomSpan(r).draw(n);
  }

  public static Span draw(Iterable<Span> possible, Random r) {
    return new RandomSpan(r).draw(possible);
  }
}
