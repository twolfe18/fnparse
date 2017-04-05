package edu.jhu.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

/**
 * A map which associates a list of values with a given key.
 * This class provides help adding and getting values from such a map.
 *
 * @author travis
 */
public class MultiMap<K, V> {

  private HashMap<K, List<V>> map;
  private int numEntries;
  
  public MultiMap() {
    map = new HashMap<>();
  }
  
  public K sampleKeyBasedOnNumEntries(Random rand) {
    if (map.isEmpty())
      throw new IllegalStateException("map is empty, can't sample");
    int thresh = rand.nextInt(numEntries);
    int sum = 0;
    for (Entry<K, List<V>> e : map.entrySet()) {
      sum += e.getValue().size();
      if (sum >= thresh)
        return e.getKey();
    }
    throw new RuntimeException("problem: sum=" + sum + " numEntries=" + numEntries + " thresh=" + thresh);
  }
  
  public void sortValues(Comparator<V> c) {
    for (List<V> l : map.values())
      Collections.sort(l, c);
  }
  
  public int numKeys() {
    return map.size();
  }
  
  public int numEntries() {
    return numEntries;
  }
  
  public List<V> get(K key) {
    List<V> v = map.get(key);
    if (v == null)
      v = Collections.emptyList();
    return v;
  }
  
  public List<V> remove(K key) {
    List<V> r = map.remove(key);
    if (r != null)
      numEntries -= r.size();
    return r;
  }
  
  public boolean add(K key, V value) {
    List<V> vals = map.get(key);
    if (vals == null) {
      vals = new ArrayList<>();
      map.put(key, vals);
    }
    numEntries++;
    return vals.add(value);
  }
  
  public boolean addIfNotPresent(K key, V value) {
    List<V> vals = map.get(key);
    if (vals == null) {
      vals = new ArrayList<>();
      map.put(key, vals);
    }
    if (vals.contains(value))
      return false;
    numEntries++;
    return vals.add(value);
  }
  
  public Iterable<K> keySet() {
    return map.keySet();
  }
}
