package edu.jhu.hlt.fnparse.util;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * fixed size FIFO queue of numbers where the average is maintained.
 * 
 * @author travis
 */
public class QueueAverage<T extends Number> {
  private Queue<T> elems;
  private double sumOfElems;
  private int capacity;

  public QueueAverage(int capacity) {
    if (capacity < 1)
      throw new IllegalArgumentException();
    this.sumOfElems = 0d;
    this.elems = new ArrayDeque<>(capacity);
    this.capacity = capacity;
  }

  /**
   * @return null if this value has not yet at capacity and the evicted item otherwise.
   */
  public T push(T value) {
    T evicted = null;
    if (elems.size() == capacity) {
      evicted = elems.poll();
      sumOfElems -= evicted.doubleValue();
    }
    sumOfElems += value.doubleValue();
    return evicted;
  }

  public T pop() {
    if (elems.size() == 0)
      throw new IllegalStateException();
    T p = elems.poll();
    sumOfElems -= p.doubleValue();
    return p;
  }

  public double getAverage() {
    if (elems.isEmpty())
      throw new IllegalStateException("no items to average");
    return sumOfElems / elems.size();
  }

  public int size() {
    return elems.size();
  }

  public int capacity() {
    return capacity;
  }

  public boolean isFull() {
    return elems.size() == capacity;
  }

  public void clear() {
    sumOfElems = 0d;
    elems.clear();
  }
}