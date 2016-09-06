package edu.jhu.hlt.ikbp;

import java.util.List;

import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
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
    
    public boolean epoch() {
//      if (verbose) {
//      for (int i = 0; i < 120; i++) System.out.print('$');
//      System.out.println();
//      }

      // Get query
      Query q = anno.nextQuery();
      if (q == null)
        return false;
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
      for (int i = 0; i < y.length; i++) {
        double resid = y[i].getScore() - yhat[i].getScore();
        s[i].backwards(-2 * resid);
      }

      if (verbose)
        System.out.println();
      return true;
    }
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    EcbPlusAnnotator anno = EcbPlusAnnotator.build(config);
    EcbPlusSearch search = EcbPlusSearch.build(config);

    IkbpSearch.DummyTrainable t0 = new IkbpSearch.DummyTrainable("ECB+/NoTrain", search);
    t0.verbose = false;
    Trainer t = new Trainer(anno, t0);

    Log.info("starting");
    while (t.epoch());
    Log.info("done");
  }
}
