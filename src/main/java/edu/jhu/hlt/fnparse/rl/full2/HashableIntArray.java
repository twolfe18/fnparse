package edu.jhu.hlt.fnparse.rl.full2;

import java.util.Arrays;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Implements hashCode and equals on int[] (normally they are based on memory
 * location rather than values in the array).
 *
 * Should be immutable: if you change the array out from under this class, it
 * obviously can't tell that happened.
 *
 * @author travis
 */
public class HashableIntArray {

  private int[] array;
  private long hash;

  public HashableIntArray(int[] wrapped) {
    array = wrapped;
    hash = wrapped[0];
    for (int i = 1; i < wrapped.length; i++)
      hash = Hash.mix64(hash, wrapped[i]);
  }

  public int get(int i) {
    return array[i];
  }

  public int length() {
    return array.length;
  }

  public long hashCode64() {
    return hash;
  }

  @Override
  public int hashCode() {
    return (int) (hash & 0xFFFFFFFF);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof HashableIntArray) {
      HashableIntArray a = (HashableIntArray) other;
      if (hash != a.hash)
        return false;
      return Arrays.equals(array, a.array);
    }
    return false;
  }
}
