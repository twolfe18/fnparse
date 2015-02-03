package edu.jhu.hlt.fnparse.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface HasId {

  public String getId();

  public static <T extends HasId> Map<String, T> indexOnId(Collection<T> ts) {
    Map<String, T> m = new HashMap<>();
    for (T t : ts) {
      T old = m.put(t.getId(), t);
      if (old != null) {
        throw new RuntimeException(
            t.getId() + " has two values, " + t + " and " + old);
      }
    }
    return m;
  }

  public static <T extends HasId> void writeItemsById(
      List<T> ts, DataOutputStream dos) throws IOException {
    for (T t : ts)
      dos.writeUTF(t.getId());
  }

  public static <T extends HasId> List<T> readItemsById(
      List<T> superset, DataInputStream hasIds, int n) throws IOException {
    Map<String, T> byId = HasId.indexOnId(superset);
    List<T> p = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String id = hasIds.readUTF();
      T t = byId.get(id);
      if (t == null)
        throw new RuntimeException("didn't find value for " + id);
      p.add(t);
    }
    return p;
  }
}
