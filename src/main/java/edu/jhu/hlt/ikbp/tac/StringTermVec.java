package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import edu.jhu.hlt.concrete.Communication;

/**
 * Holds tf not idf.
 */
public class StringTermVec implements Iterable<Entry<String, Double>>, Serializable {
  private static final long serialVersionUID = -284690402445952436L;

  private Map<String, Double> tf;
  private double z;
  
  public StringTermVec() {
    tf = new HashMap<>();
    z = 0;
  }
  
  public StringTermVec(Communication c, boolean normalizeNumbers) {
    this();
    if (c == null)
      throw new IllegalArgumentException();
    for (String s : IndexCommunications.terms(c, normalizeNumbers))
      add(s, 1);
  }
  
  public Double getCount(String word) {
    return tf.get(word);
  }
  
  public double getTotalCount() {
    return z;
  }

  public void add(StringTermVec other) {
    for (Entry<String, Double> e : other.tf.entrySet())
      add(e.getKey(), e.getValue());
  }
  
  public void add(String word, double count) {
    double prev = tf.getOrDefault(word, 0d);
    tf.put(word, prev + count);
    z += count;
  }

  @Override
  public Iterator<Entry<String, Double>> iterator() {
    return tf.entrySet().iterator();
  }
}