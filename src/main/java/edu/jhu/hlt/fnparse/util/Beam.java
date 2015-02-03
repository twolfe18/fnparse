package edu.jhu.hlt.fnparse.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

public interface Beam<T> extends Iterable<T> {

  /**
   * How many items are on the beam.
   */
  public int size();

  /**
   * Maximum number of items allowed on the beam.
   */
  public int width();

  public T pop();
  public T peek();

  /**
   * Returns true if this item was added to the beam.
   */
  public boolean push(T item, double score);


  /**
   * An efficient width-1 beam.
   */
  public static class Beam1<T> implements Beam<T> {
    private T item;
    private double score;
    @Override
    public Iterator<T> iterator() {
      return Arrays.asList(item).iterator();
    }
    @Override
    public int size() {
      return item == null ? 0 : 1;
    }
    @Override
    public int width() {
      return 1;
    }
    @Override
    public T pop() {
      T temp = item;
      item = null;
      return temp;
    }
    @Override
    public T peek() {
      return item;
    }
    @Override
    public boolean push(T item, double score) {
      if (this.item == null || this.score < score) {
        this.item = item;
        this.score = score;
        return true;
      } else {
        return false;
      }
    }
  }

  public static class Item<T> implements Comparable<Item<?>> {
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

  /**
   * A beam implemented by a TreeSet.
   */
  public static class BeamN<T> implements Beam<T> {
    private SortedSet<Item<T>> beam;
    private int width;

    public BeamN(int width) {
      this.width = width;
      this.beam = new TreeSet<>();
    }

    public boolean push(T item, double score) {
      Item<T> i = new Item<>(item, score);
      beam.add(i);
      if (beam.size() > width) {
        Item<T> evict = beam.last();
        beam.remove(evict);
        return evict.item != item;
      } else {
        return true;
      }
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

    @Override
    public int size() {
      return beam.size();
    }

    @Override
    public int width() {
      return width;
    }

    @Override
    public T peek() {
      return beam.first().item;
    }
  }
}
