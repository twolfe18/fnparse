package edu.jhu.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.prim.tuple.Pair;

/**
 * Without replacement.
 *
 * @author travis
 */
public class ChooseOne<T> {
  private Random rand;
  private List<T> obj;
  private DoubleArrayList weights;
  private double Z;
  private int offers;

  public ChooseOne(int seed) {
    this(new Random(seed));
  }
  
  public ChooseOne(Random rand) {
    this.rand = rand;
    obj = new ArrayList<>();
    weights = new DoubleArrayList();
    Z = 0;
    offers = 0;
  }
  
  public void offer(T object, double weight) {
    if (weight < 0)
      throw new IllegalArgumentException();
    if (weight == 0)
      return;
    obj.add(object);
    weights.add(weight);
    Z += weight;
    offers++;
  }
  
  public int numOffers() {
    return offers;
  }
  
  public void clear() {
    obj.clear();
    weights.clear();
    Z = 0;
    offers = 0;
  }
  
  public void optimizeForManyDraws() {
    List<Pair<T, Double>> items = new ArrayList<>();
    int n = obj.size();
    for (int i = 0; i < n; i++)
      items.add(new Pair<>(obj.get(i), weights.get(i)));
    Collections.sort(items, new Comparator<Pair<?, Double>>() {
      @Override
      public int compare(Pair<?, Double> o1, Pair<?, Double> o2) {
        double s1 = o1.get2();
        double s2 = o2.get2();
        if (s1 > s2)
          return -1;
        if (s1 < s2)
          return +1;
        return 0;
      }
    });
    clear();
    for (Pair<T, Double> p : items)
      offer(p.get1(), p.get2());
  }
  
  public T choose() {
    double t = rand.nextDouble() * Z;
    double s = 0;
    int n = obj.size();
    if (n == 0)
      throw new IllegalStateException("no positive-weight items have been added!");
    for (int i = 0; i < n; i++) {
      s += weights.get(i);
      if (s >= t)
        return obj.get(i);
    }
    throw new RuntimeException("Z=" + Z + " t=" + t + " s=" + s + " n=" + n + " weights=" + weights);
  }
}