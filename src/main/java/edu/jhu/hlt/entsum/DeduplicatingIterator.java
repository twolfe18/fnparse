package edu.jhu.hlt.entsum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.StringTermVec;

/**
 * Does a greedy one-pass filtering of sentences which seem like duplicates.
 *
 * @author travis
 */
public class DeduplicatingIterator<T> implements Iterator<T> {

  private Function<T, CluewebLinkedSentence> viewSent;
  private Iterator<T> inner;
  private T next;
  private Set<String> uniq;
  private List<StringTermVec> outputVecs;
  private ComputeIdf df;
  private double cosineThresh;
  
  public DeduplicatingIterator(Iterator<T> inner, Function<T, CluewebLinkedSentence> viewSent, ComputeIdf df, double cosineThresh) {
    this.viewSent = viewSent;
    this.cosineThresh = cosineThresh;
    this.df = df;
    this.inner = inner;
    this.uniq = new HashSet<>();
    this.outputVecs = new ArrayList<>();
    advance();
  }
  
  private void advance() {
    next = null;
    iter:
    while (next == null && inner.hasNext()) {
      T sp = inner.next();
      CluewebLinkedSentence sent = viewSent.apply(sp);
      if (!uniq.add(sent.hashHex()))
        continue;
      
      StringTermVec tv = new StringTermVec();
      for (String t : sent.getAllWords(new ArrayList<>(), false))
        tv.add(t, 1);
      for (StringTermVec prev : outputVecs) {
        double c = df.tfIdfCosineSim(tv, prev);
        if (c > cosineThresh)
          continue iter;
      }
      outputVecs.add(tv);
      
      next = sp;
    }

  }

  @Override
  public boolean hasNext() {
    return next != null;
  }

  @Override
  public T next() {
    T sp = next;
    advance();
    return sp;
  }
}