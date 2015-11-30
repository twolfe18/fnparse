package edu.jhu.hlt.fnparse.data;

import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.prim.tuple.Pair;

/**
 * FrameRolePacking does (f,k) -> int
 * The roles in FrameInstance does k -> f -> role name
 * This class does (f,k) -> role name -> int
 * This collapses role names across frames, e.g. f1.Agent == f2.Agent
 *
 * For PB this should be the case starting from FrameInstance, but may not be
 * depending on how the Frames were created, so this ensures role names are
 * uniform across frames.
 *
 * For FN this packs roles into a global namespace, ignoring frame (which is
 * not really a good thing to do a priori). The original design is better for
 * making predictions, where 1..K compactly represents the set of roles possible
 * for a given frame.
 *
 * @author travis
 */
public class RolePacking {

  public static Map<Pair<Frame, Integer>, Integer> pack(FrameIndex fi) {
    Map<Pair<Frame, Integer>, Integer> m = new HashMap<>();
    // Create a pool of all role names
    Map<String, Integer> roleNames = new HashMap<>();
    for (Frame f : fi.allFrames()) {
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Integer i = roleNames.get(f.getRole(k));
        if (i == null) {
          i = roleNames.size();
          roleNames.put(f.getRole(k), i);
        }
        Pair<Frame, Integer> key = new Pair<>(f, k);
        m.put(key, i);
      }
    }
    return m;
  }
}
