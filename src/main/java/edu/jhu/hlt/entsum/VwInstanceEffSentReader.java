package edu.jhu.hlt.entsum;

import java.io.IOException;
import java.util.Iterator;

import edu.jhu.prim.tuple.Pair;

public class VwInstanceEffSentReader implements Iterator<Pair<EffSent, VwInstance>>, AutoCloseable {

  private VwInstanceReader verbs;
  private EffSent.Iter sents;
  private EffSent curSent;
  private int curSentIdx;
  
  public VwInstanceEffSentReader(VwInstanceReader verbs, EffSent.Iter sents) {
    this.verbs = verbs;
    this.sents = sents;
    this.curSentIdx = -1;
  }

  @Override
  public void close() throws IOException {
    verbs.close();
    sents.close();
  }

  @Override
  public boolean hasNext() {
    return verbs.hasNext();
  }

  @Override
  public Pair<EffSent, VwInstance> next() {
    VwInstance v = verbs.next();
    while (v.loc.sentIdx > curSentIdx) {
      curSent = sents.next();
      curSentIdx++;
    }
    assert v.loc.sentIdx == curSentIdx;
    return new Pair<>(curSent, v);
  }
}
