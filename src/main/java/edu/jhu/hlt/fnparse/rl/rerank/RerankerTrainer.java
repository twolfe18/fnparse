package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;

/**
 * Training logic can get out of hand (e.g. checking against dev data, etc).
 * Put all that junk in here rather than in Reranker.
 *
 * @author travis
 */
public class RerankerTrainer {
  public static final Logger LOG = Logger.getLogger(RerankerTrainer.class);

  private Random rand;
  private int epochs;
  private int threads;
  private int batchSize;

  public RerankerTrainer() {
    this(new Random(9001), 3, 1, 1);
  }

  public RerankerTrainer(Random rand, int epochs, int threads, int batchSize) {
    this.rand = rand;
    this.epochs = epochs;
    this.threads = threads;
    this.batchSize = batchSize;
  }

  public Reranker train(Params theta, int beamWidth, ItemProvider ip) {
    Reranker r = new Reranker(theta, beamWidth);
    ExecutorService es = Executors.newWorkStealingPool(threads);
    int n = ip.size();
    for (int epoch = 0; epoch < epochs; epoch++) {
      int updated = 0;
      LOG.info("[train] startring epoch " + (epoch+1) + "/" + epochs
          + " which will have " + (n/batchSize) + " updates");
      for (int i = 0; i < n; i += batchSize) {
        try {
          updated += trainBatch(r, es, ip);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        System.out.print("*");
      }
      System.out.println();
      LOG.info("[train] updated " + updated);
      if (updated == 0)
        break;  // unlikely...
    }
    es.shutdown();
    return r;
  }

  private int trainBatch(Reranker r, ExecutorService es, ItemProvider ip) throws InterruptedException, ExecutionException {
    boolean verbose = false;
    List<Future<Update>> updates = new ArrayList<>();
    int n = ip.size();
    for (int i = 0; i < batchSize; i++) {
      int idx = rand.nextInt(n);
      FNParse y = ip.label(idx);
      List<Item> rerank = ip.items(idx);
      if (verbose)
        LOG.info("[trainBatch] submitting " + idx);
      updates.add(es.submit(() -> r.new Update(y, rerank)));
    }
    //es.awaitTermination(99, TimeUnit.HOURS);
    if (verbose)
      LOG.info("[trainBatch] applying updates");
    int updated = 0;
    for (Future<Update> u : updates) {
      Update up = u.get();
      if (up.apply())
        updated++;
    }
    return updated;
  }

}
