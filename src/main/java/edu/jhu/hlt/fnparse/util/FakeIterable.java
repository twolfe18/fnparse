package edu.jhu.hlt.fnparse.util;

import java.util.Iterator;

/**
 * Lifts an Iterator to an Iterable where you can only call iterator() once
 * (yes, I know this isn't really an Iterable...).
 * 
 * This is pretty much made because java for loops don't support Iterators,
 * only Iterables...
 *
 * @author travis
 */
public class FakeIterable<T> implements Iterable<T> {

  private Iterator<T> iterator;

  public FakeIterable(Iterator<T> iter) {
    this.iterator = iter;
  }

  @Override
  public Iterator<T> iterator() {
    if (iterator == null)
      throw new RuntimeException("can only call this once!");
    Iterator<T> r = iterator;
    iterator = null;
    return r;
  }
}
