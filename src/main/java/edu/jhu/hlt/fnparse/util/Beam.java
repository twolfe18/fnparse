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

  public Beam.Item<T> popItem();
  public Beam.Item<T> peekItem();

  /**
   * Returns true if this item was added to the beam.
   */
  public boolean push(T item, double score);

  public boolean push(Beam.Item<T> item);


  public static <T> Beam<T> getMostEfficientImpl(int beamSize) {
    if (beamSize == 1)
      return new Beam1<>();
    if (beamSize == 4)
      return new Beam4<>();
    return new BeamN<>(beamSize);
  }

  /**
   * An efficient width-4 beam.
   */
  public static class Beam4<T> implements Beam<T> {
    private T i1, i2, i3, i4;
    private double s1, s2, s3, s4;
    private int size = 0;
    @Override
    public Iterator<T> iterator() {
      throw new RuntimeException("implement me");
    }
    @Override
    public int size() {
      return size;
    }
    @Override
    public int width() {
      return 4;
    }
    @Override
    public T pop() {
      assert size > 0;
      T r = i1;
      i1 = i2;
      i2 = i3;
      i3 = i4;
      s1 = s2;
      s2 = s3;
      s3 = s4;
      size--;
      return r;
    }
    @Override
    public T peek() {
      assert size > 0;
      return i1;
    }
    @Override
    public Item<T> popItem() {
      assert size > 0;
      Item<T> i = new Item<>(i1, s1);
      i1 = i2;
      i2 = i3;
      i3 = i4;
      s1 = s2;
      s2 = s3;
      s3 = s4;
      size--;
      return i;
    }
    @Override
    public Item<T> peekItem() {
      return new Item<>(i1, s1);
    }
    @Override
    public boolean push(T item, double score) {
      if (score > s1 || size < 1) {
        i4 = i3;
        i3 = i2;
        i2 = i1;
        i1 = item;
        s4 = s3;
        s3 = s2;
        s2 = s1;
        s1 = score;
        if (size < 4) size++;
        return true;
      } else if (score > s2 || size < 2) {
        i4 = i3;
        i3 = i2;
        i2 = item;
        s4 = s3;
        s3 = s2;
        s2 = score;
        if (size < 4) size++;
        return true;
      } else if (score > s3 || size < 3) {
        i4 = i3;
        i3 = item;
        s4 = s3;
        s3 = score;
        if (size < 4) size++;
        return true;
      } else if (score > s4 || size < 4) {
        i4 = item;
        s4 = score;
        if (size < 4) size++;
        return true;
      } else {
        return false;
      }
    }
    @Override
    public boolean push(Item<T> item) {
      return push(item.item, item.score);
    }
  }

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
      if (size() == 0)
        throw new RuntimeException();
      T temp = item;
      item = null;
      return temp;
    }
    @Override
    public Beam.Item<T> popItem() {
      if (size() == 0)
        throw new RuntimeException();
      Beam.Item<T> i = new Beam.Item<T>(item, score);
      item = null;
      return i;
    }
    @Override
    public T peek() {
      if (size() == 0)
        throw new RuntimeException();
      return item;
    }
    @Override
    public Beam.Item<T> peekItem() {
      if (size() == 0)
        throw new RuntimeException();
      return new Beam.Item<T>(item, score);
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
    @Override
    public boolean push(Beam.Item<T> item) {
      return push(item.item, item.score);
    }
  }

  public static class Item<T> implements Comparable<Item<?>> {
    private T item;
    private double score;

    public Item(T item, double score) {
      this.item = item;
      this.score = score;
    }

    @Override
    public String toString() {
      return String.format("(Beam.Item %s %+.2f)", item, score);
    }

    @Override
    public int compareTo(Item<?> o) {
      if (o.score > score)
        return 1;
      if (o.score < score)
        return -1;
      return 0;
    }

    public T getItem() { return item; }
    public double getScore() { return score; }
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

    @Override
    public boolean push(Beam.Item<T> item) {
      return push(item.item, item.score);
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
    public Beam.Item<T> popItem() {
      if (beam.size() == 0)
        throw new RuntimeException();
      Item<T> it = beam.first();
      beam.remove(it);
      return it;
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
      if (beam.size() == 0)
        throw new RuntimeException();
      return beam.first().item;
    }

    @Override
    public Beam.Item<T> peekItem() {
      if (beam.size() == 0)
        throw new RuntimeException();
      return beam.first();
    }
  }
}
