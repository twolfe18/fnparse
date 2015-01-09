package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Counts<T> {
  private Map<T, Integer> counts = new HashMap<T, Integer>();
  private int total = 0;

  public int getCount(T t) {
    Integer c = counts.get(t);
    return c == null ? 0 : c;
  }

  public double getProportion(T t) {
    if (total == 0)
      return 0d;
    return ((double) getCount(t)) / total;
  }

  public Iterable<Entry<T, Integer>> entrySet() {
    return counts.entrySet();
  }

  public int increment(T t) {
    return update(t, 1);
  }

  public int update(T t, int delta) {
    int c = getCount(t);
    if (c < 0)
      throw new RuntimeException("item: " + t);
    counts.put(t, c + delta);
    total += delta;
    return c;
  }

  public int numNonZero() {
    return counts.size();
  }

  public int getTotalCount() {
    return total;
  }

  public String toString() {
    return counts.toString();
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public List<T> getKeysSorted() {
    List<T> items = new ArrayList<>();
    items.addAll(counts.keySet());
    Collections.sort((List<Comparable>) items);
    return items;
  }

  public List<T> getKeysSortedByCount(final boolean descending) {
    List<T> items = new ArrayList<>();
    items.addAll(counts.keySet());
    Collections.sort(items, new Comparator<T>() {
      @Override
      public int compare(T arg0, T arg1) {
        int c0 = getCount(arg0);
        int c1 = getCount(arg1);
        if(c0 == c1) return 0;
        if(c1 > c0 ^ descending)
          return 1;
        else
          return -1;
      }
    });
    return items;
  }

  public List<T> countIsAtLeast(int minCount) {
    if(minCount <= 0)
      throw new IllegalArgumentException();
    List<T> l = new ArrayList<T>();
    for(T t : counts.keySet())
      if(getCount(t) >= minCount)
        l.add(t);
    return l;
  }

  public List<T> countIsLessThan(int maxCount) {
    if(maxCount <= 0)
      throw new IllegalArgumentException();
    List<T> l = new ArrayList<T>();
    for(T t : counts.keySet())
      if(getCount(t) < maxCount)
        l.add(t);
    return l;
  }

  public void clear() { counts.clear(); }

  public Comparator<T> ascendingComparator() {
    return new Comparator<T>() {
      @Override
      public int compare(T arg0, T arg1) {
        return getCount(arg1) - getCount(arg0);
      }
    };
  }

  public Comparator<T> desendingComparator() {
    return new Comparator<T>() {
      @Override
      public int compare(T arg0, T arg1) {
        return getCount(arg0) - getCount(arg1);
      }
    };
  }
}
