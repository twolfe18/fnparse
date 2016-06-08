package edu.jhu.hlt.uberts.auto;

import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.rules.Env.Trie3;
import edu.jhu.prim.bimap.ObjectObjectBimap;

/**
 * Hashable Term[] representing a trigger, a key to {@link Trie3}.
 *
 * Stores a global index, which is used by {@link Trie3}.
 *
 * Terms have variable names, so this can also enforce equality constraints
 * implicitly by variable name equality.
 *
 * @author travis
 */
public class Trigger {

  private Term[] terms;
  private int index;
  private long hc;

  public Trigger(Term[] terms, int index) {
    if (terms == null)
      throw new IllegalArgumentException();
    this.terms = terms;
    this.index = index;
    computeHashcode();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("(Trigger i=" + index);
    for (int i = 0; i < terms.length; i++) {
      sb.append(' ');
      sb.append(terms[i]);
    }
    sb.append(')');
    return sb.toString();
  }

  /**
   * Returns true if these triggers have
   * 1) the same {@link Relation}/functors, in the same order
   * 2) the same equality constraints (induced from variable names)
   */
  public boolean equivalent(Trigger other) {
    if (terms.length != other.terms.length)
      return false;
    for (int i = 0; i < terms.length; i++) {
      Term t1 = terms[i];
      Term t2 = other.terms[i];
      if (!t1.relName.equals(t2.relName))
        return false;
      if (t1.getNumArgs() != t2.getNumArgs())
        return false;
    }
    // Try to build a bijection between argument names
    // If you can (and the relations match), then the equality constraints are equivalent
    ObjectObjectBimap<String, String> bij = new ObjectObjectBimap<>();
    for (int i = 0; i < terms.length; i++) {
      Term t1 = terms[i];
      Term t2 = other.terms[i];
      for (int argIdx : t1.getArgIndices()) {
        String a1 = t1.getArgName(argIdx);
        String a2 = t2.getArgName(argIdx);
        try {
          bij.put(a1, a2);
        } catch (Exception e) {
          return false;
        }
      }
    }
    return true;
  }


  public int getIndex() {
    return index;
  }

  /** Returns the old value */
  public int setIndex(int index) {
    int old = this.index;
    this.index = index;
    computeHashcode();
    return old;
  }

  private void computeHashcode() {
    hc = Hash.mix64(index, terms.length);
    for (int i = 0; i < terms.length; i++)
      hc = Hash.mix64(hc, hash(terms[i]));
  }

  public static long hash(Term t) {
    long h = Hash.hash(t.relName);
    for (int argIdx : t.getArgIndices()) {
      h = Hash.mix64(h, argIdx);
      h = Hash.mix64(h, Hash.hash(t.getArgName(argIdx)));
    }
    return h;
  }

  public Term get(int i) {
    return terms[i];
  }

  public int length() {
    return terms.length;
  }

  @Override
  public int hashCode() {
    return (int) hc;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof Trigger) {
      Trigger t = (Trigger) o;
      assert (hc == t.hc) == (terms == t.terms);
      return terms == t.terms;
    }
    return false;
  }
}
