package edu.jhu.hlt.fnparse.rl.full;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

public class GroupBy<S, K> implements Iterator<List<S>> {
  private PeekingIterator<S> itr;
  private Function<S, K> key;
  private K curKey;
  private List<S> curGroup;

  public GroupBy(Iterator<S> items, Function<S, K> keyFunction) {
    itr = Iterators.peekingIterator(items);
    key = keyFunction;
    advance();
  }

  private void advance() {
    if (!itr.hasNext()) {
      curKey = null;
      curGroup = null;
      return;
    }
    S c = itr.peek();
    curKey = key.apply(c);
    curGroup = new ArrayList<>();
    while (itr.hasNext() && curKey.equals(key.apply(itr.peek())))
      curGroup.add(itr.next());
  }

  @Override
  public boolean hasNext() {
    return curKey != null;
  }

  @Override
  public List<S> next() {
    List<S> r = curGroup;
    advance();
    return r;
  }
}