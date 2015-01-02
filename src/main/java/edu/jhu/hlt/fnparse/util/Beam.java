package edu.jhu.hlt.fnparse.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

public class Beam<T> implements Iterable<T> {

  static class Item<T> implements Comparable<Item<?>> {
    T item;
    double score;

    public Item(T item, double score) {
      this.item = item;
      this.score = score;
    }

    @Override
    public int compareTo(Item<?> o) {
      if (o.score > score)
        return 1;
      if (o.score < score)
        return -1;
      return 0;
    }
  }

  private SortedSet<Item<T>> beam;
  private int width;

  public Beam(int width) {
    this.width = width;
    this.beam = new TreeSet<>();
  }

  /**
   * How many items are on the beam.
   */
  public int getSize() {
    return beam.size();
  }

  /**
   * Maximum number of items allowed on the beam.
   */
  public int getWidth() {
    return width;
  }

  public void push(T item, double score) {
    Item<T> i = new Item<>(item, score);
    beam.add(i);
    if (beam.size() > width)
      beam.remove(beam.last());
  }

  /**
   * Returns the k item with the highest scores and adds them to addTo.
   * Highest score items are added to addTo first.
   */
  public void pop(Collection<T> addTo, int k) {
    for (int i = 0; i < k && beam.size() > 0; i++)
      addTo.add(pop());
  }

  /**
   * Returns the item with the highest score.
   */
  public T pop() {
    if (beam.size() == 0)
      return null;
    Item<T> it = beam.first();
    beam.remove(it);
    return it.item;
  }

  @Override
  public Iterator<T> iterator() {
    return beam.stream().map(i -> i.item).iterator();
  }
}
