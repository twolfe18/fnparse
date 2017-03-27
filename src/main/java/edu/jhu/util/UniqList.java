package edu.jhu.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class for making lists of unique items.
 *
 * @author travis
 */
public class UniqList<T> {
  
  private Set<T> seen;
  private List<T> list;
  private int adds;
  
  public UniqList() {
    seen = new HashSet<>();
    list = new ArrayList<>();
    adds = 0;
  }

  public boolean add(T t) {
    if (seen.add(t)) {
      adds++;
      list.add(t);
      return true;
    }
    return false;
  }
  
  public int numAdds() {
    return adds;
  }
  
  public int size() {
    return list.size();
  }
  
  public List<T> getList() {
    return list;
  }
  
  public Set<T> getSet() {
    return seen;
  }
}
