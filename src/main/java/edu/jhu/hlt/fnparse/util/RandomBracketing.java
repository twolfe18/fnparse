package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.tutils.Span;

/**
 * Makes random bracketings in a bottom up fashion. Useful for
 * comparison to latent syntax (which should have statistically
 * significantly higher recall).
 * 
 * @author travis
 */
public class RandomBracketing {
  private Random rand;

  public RandomBracketing(Random r) {
    this.rand = r;
  }
  
  public void bracket(int n, Collection<Span> addTo) {
    List<Span> fronteer = new ArrayList<>();
    // initialize as all preterminals
    for (int i = 0; i < n; i++) {
      fronteer.add(Span.widthOne(i));
      addTo.add(Span.widthOne(i));
    }
    while (fronteer.size() > 1) {
      // merge (i,i+1) for some random i
      int i = rand.nextInt(fronteer.size() - 1);
      Span l = fronteer.get(i);
      Span r = fronteer.get(i + 1);
      Span merged = Span.getSpan(l.start, r.end);
      // this preserves the ordering of spans
      fronteer.set(i, merged);
      fronteer.remove(i + 1);
      addTo.add(merged);
    }
    addTo.add(fronteer.get(0));
  }
}
