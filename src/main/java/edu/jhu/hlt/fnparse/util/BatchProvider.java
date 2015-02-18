package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Provides batches by way of indices into the full dataset.
 *
 * @author travis
 */
public class BatchProvider {

  private Random rand;
  private List<Integer> permutation;
  private int pPos;

  public BatchProvider(Random rand, int n) {
    this.rand = rand;
    permutation = new ArrayList<>();
    for (int i = 0; i < n; i++)
      permutation.add(i);
    Collections.shuffle(permutation, rand);
    pPos = 0;
  }

  public List<Integer> getBatch(int batchSize, boolean withReplacement) {
    if (withReplacement)
      return getBatchWithReplacement(batchSize);
    else
      return getBatchWithoutReplacement(batchSize);
  }

  public List<Integer> getBatchWithoutReplacement(int batchSize) {
    if (batchSize > this.size())
      throw new IllegalArgumentException();
    if (pPos + batchSize > permutation.size()) {
      Collections.shuffle(permutation, rand);
      pPos = 0;
    }
    List<Integer> batch = permutation.subList(pPos, pPos + batchSize);
    pPos += batchSize;
    return batch;
  }

  public List<Integer> getBatchWithReplacement(int batchSize) {
    if (batchSize > this.size())
      throw new IllegalArgumentException();
    List<Integer> batch = new ArrayList<>(batchSize);
    int n = this.size();
    for (int i = 0; i < n; i++)
      batch.add(rand.nextInt(n));
    return batch;
  }

  /** Returns how many elements are in the underlying set of instances */
  public int size() {
    return permutation.size();
  }
}
