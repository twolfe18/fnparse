package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.ikbp.features.EcbPlusMentionFeatureExtractor;
import edu.jhu.hlt.ikbp.features.MentionFeatureExtractor;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * Train and test a IKBP service. Backwards chaining for the work I need to do.
 *
 * @author travis
 */
public class EcbPlusExperiment {

  static class Trainer {
    private IkbpAnnotator anno;
    private IkbpSearch.Trainable search;
    private boolean verbose = false;
    
    public Trainer(IkbpAnnotator anno, IkbpSearch.Trainable search) {
      this.anno = anno;
      this.search = search;
    }
    
    public double epoch(boolean learn) {
//      if (verbose) {
//      for (int i = 0; i < 120; i++) System.out.print('$');
//      System.out.println();
//      }

      // Get query
      Query q = anno.nextQuery();
      if (q == null)
        return -1;
      if (verbose)
        DataUtil.showQuery(q);

      // Search
      List<Pair<Response, Adjoints>> r = search.search2(q);
      
      // Elicit response from annotator
      Response[] y = new Response[r.size()];
      Response[] yhat = new Response[r.size()];
      Adjoints[] s = new Adjoints[r.size()];
      for (int i = 0; i < y.length; i++) {
        y[i] = r.get(i).get1();
        yhat[i] = anno.annotate(q, y[i]);
        s[i] = r.get(i).get2();
        
        if (verbose) {
          DataUtil.showResponse(yhat[i]);
          System.out.println("\tlabel:       " + y[i].getScore());
        }
      }
      
      // Perform the update
      // Currently: minimize squared error
      double totalResid = 0;
      for (int i = 0; i < y.length; i++) {
        double resid = y[i].getScore() - yhat[i].getScore();
        totalResid += resid * resid;
        if (learn)
          s[i].backwards(-2 * resid);
      }

      if (verbose)
        System.out.println();
      if (y.length == 0)
        return 1;
      return totalResid / y.length;
    }
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    EcbPlusXmlStore xmlDocs = new EcbPlusXmlStore(config);
    Random rand = config.getRandom();
    EcbPlusAnnotator anno = new EcbPlusAnnotator(xmlDocs, rand);
    EcbPlusSearch search = new EcbPlusSearch(xmlDocs);

    boolean train = config.getBoolean("train", true);
    IkbpSearch.Trainable t0;
    if (!train) {
      t0 = new IkbpSearch.DummyTrainable("ECB+/NoTrain", search);
      ((IkbpSearch.DummyTrainable) t0).verbose = false;
    } else {
      File parseyEcbPlus = config.getExistingDir("data.ecbplus.parsey",
          new File("data/parma/ecbplus/ECB+_LREC2014/ECB+_pmp_conll"));
      MentionFeatureExtractor mfe = new EcbPlusMentionFeatureExtractor(parseyEcbPlus, xmlDocs);
      t0 = new IkbpSearch.FeatureBased(search, mfe, rand);
    }
    Trainer t = new Trainer(anno, t0);

    // Progressive validation
    double totalLoss = 0;
    Average avgLoss = new Average.Exponential(0.9);
    for (int i = 0; i < 600; i++) {
      double l = t.epoch(true);
      assert !Double.isNaN(l) && Double.isFinite(l);
      totalLoss += l;
      avgLoss.add(l);
      if (i % 10 == 0) {
        System.out.println("i=" + i
            + "\tloss=" + l
            + "\tavgLoss=" + (totalLoss / (i+1))
            + "\tlocalAvgLoss=" + avgLoss.getAverage());
      }
    }
  }
}
