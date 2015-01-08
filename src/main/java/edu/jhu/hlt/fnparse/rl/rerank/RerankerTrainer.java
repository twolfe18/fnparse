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
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.MultiTimer;

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
  private MultiTimer timer = new MultiTimer();

  public RerankerTrainer() {
    this(new Random(9001), 3, 1, 1);
  }

  public RerankerTrainer(Random rand, int epochs, int threads, int batchSize) {
    this.rand = rand;
    this.epochs = epochs;
    this.threads = threads;
    this.batchSize = batchSize;
  }

  public Reranker train(Params.Stateful thetaStateful, int beamWidth, ItemProvider ip) {
    return train(thetaStateful, Stateless.NONE, beamWidth, ip);
  }
  public Reranker train(Params.Stateless thetaStateless, int beamWidth, ItemProvider ip) {
    return train(Stateful.NONE, thetaStateless, beamWidth, ip);
  }
  public Reranker train(Params.Stateful thetaStateful, Params.Stateless thetaStateless, int beamWidth, ItemProvider ip) {
    LOG.info("[train] batchSize=" + batchSize + " epochs=" + epochs + " threads=" + threads);

    // If you don't make an update after this many batches, exit learning early
    final int inARow = 5;

    Reranker r = new Reranker(thetaStateful, thetaStateless, beamWidth);
    ExecutorService es = null;
    if (threads > 1)
      es = Executors.newWorkStealingPool(threads);
    int n = ip.size();
    int curRun = 0;
    try {
      for (int epoch = 0; epoch < epochs; epoch++) {
        int updated = 0;
        LOG.info("[train] startring epoch " + (epoch+1) + "/" + epochs
            + " which will have " + (n/batchSize) + " updates");
        for (int i = 0; i < n; i += batchSize) {
          int u = trainBatch(r, es, ip);
          updated += u;
          System.out.print("*");
          if (u == 0) {
            curRun++;
            if (curRun == inARow) {
              System.out.println();
              LOG.info("[train] exiting early in the middle of epoch " + (epoch+1)
                  + " of " + epochs + " because we made an entire pass over " + n
                  + " data points without making a single subgradient step");
              break;
            }
          } else {
            curRun = 0;
          }
        }

        System.out.println();
        LOG.info("[train] updated " + updated);
      }
      if (es != null)
        es.shutdown();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    LOG.info("[train] times:\n" + timer);
    return r;
  }

  private int trainBatch(Reranker r, ExecutorService es, ItemProvider ip) throws InterruptedException, ExecutionException {
    timer.start("trainBatch");
    boolean verbose = false;
    int n = ip.size();
    List<Update> finishedUpdates = new ArrayList<>();
    if (es == null) {
      LOG.info("[trainBatch] running serial");
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[trainBatch] submitting " + idx);
        finishedUpdates.add(r.new Update(y, rerank));
      }
    } else {
      LOG.info("[trainBatch] running with ExecutorService");
      List<Future<Update>> updates = new ArrayList<>();
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[trainBatch] submitting " + idx);
        updates.add(es.submit(() -> r.new Update(y, rerank)));
      }
      for (Future<Update> u : updates)
        finishedUpdates.add(u.get());
    }
    if (verbose)
      LOG.info("[trainBatch] applying updates");
    assert finishedUpdates.size() == batchSize;
    int updated = 0;
    for (Update u : finishedUpdates) {
      if (u.apply())
        updated++;
    }
    timer.stop("trainBatch");
    return updated;
  }

}
