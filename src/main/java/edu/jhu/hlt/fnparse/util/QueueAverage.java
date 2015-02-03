package edu.jhu.hlt.fnparse.util;

import java.util.ArrayDeque;
import java.util.Queue;

import org.apache.log4j.Logger;

/**
 * fixed size FIFO queue of numbers where the mean and variance is maintained.
 * 
 * @author travis
 */
public class QueueAverage<T extends Number> {
  public static final Logger LOG = Logger.getLogger(QueueAverage.class);

  private Queue<T> elems;
  private double sumOfElems;
  private double sumOfElemsSq;
  private int capacity;

  public QueueAverage(int capacity) {
    if (capacity < 1)
      throw new IllegalArgumentException();
    this.sumOfElems = 0d;
    this.sumOfElemsSq = 0d;
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
      double ev = evicted.doubleValue();
      sumOfElems -= ev;
      sumOfElemsSq -= ev * ev;
    }
    elems.add(value);
    double vv = value.doubleValue();
    sumOfElems += vv;
    sumOfElemsSq += vv * vv;
    return evicted;
  }

  public T pop() {
    if (elems.size() == 0)
      throw new IllegalStateException();
    T p = elems.poll();
    double pv = p.doubleValue();
    sumOfElems -= pv;
    sumOfElemsSq -= pv * pv;
    return p;
  }

  public double getAverage() {
    if (elems.isEmpty())
      throw new IllegalStateException("no items to average");
    return sumOfElems / elems.size();
  }

  public double getVariance() {
    // var = E[x^2] - E[x]^2
    double mu = getAverage();
    double exs = sumOfElemsSq / elems.size();
    double var = exs - mu;

    double tol = 1e-10;
    if (var < tol) {
      LOG.warn("variance is too small: " + var);
      return tol;
    } else {
      return var;
    }
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
    sumOfElemsSq = 0d;
    elems.clear();
  }
}