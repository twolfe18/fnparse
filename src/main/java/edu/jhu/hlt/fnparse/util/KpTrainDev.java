package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

public class KpTrainDev {
  public static Logger LOG = Logger.getLogger(KpTrainDev.class);
  /**
   * @param K is how many independent dev sets to produce
   * @param p is the proportion of the data that should go into a dev set, in (0,1)
   * @param all is the set of all items to be split up
   * @return a length K+1 array of List<T>. The first element is the list of
   * points that do not fall in any dev set. The remaining K lists are the dev
   * sets.
   */
  public static <T> List<T>[] kpSplit(
      int K, double p, Collection<T> all, Random rand) {
    LOG.info("splitting " + all.size() + " items up into "
      + K + " dev sets of size " + p);
    @SuppressWarnings("unchecked")
    List<T>[] sets = new List[K + 1];
    for (int i = 0; i < sets.length; i++)
      sets[i] = new ArrayList<>();
    int devSize = (int) (all.size() * p); // note round down
    if (devSize * K > all.size())
      throw new IllegalArgumentException();
    List<T> allL = new ArrayList<>();
    allL.addAll(all);
    Collections.shuffle(allL, rand);
    int ptr = 1;
    for (T t : allL) {
      if (ptr > 0 && sets[ptr].size() >= devSize) {
        ptr++;
        if (ptr >= sets.length)
          ptr = 0;  // Finally, dump remaining items back in the 0th bucket
      }
      sets[ptr].add(t);
    }
    for (int i = 0; i < sets.length; i++)
      LOG.info("bucket " + i + " has " + sets[i].size() + " elements");
    return sets;
  }
}
