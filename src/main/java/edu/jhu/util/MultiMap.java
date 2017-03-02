package edu.jhu.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

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
}
