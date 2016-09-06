package edu.jhu.hlt.ikbp;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * Query a collection of documents given a query composed of a subject {@link Node} and related {@link PKB}.
 *
 * @author travis
 */
public interface IkbpSearch {
  
  Iterable<Response> search(Query q);

  /**
   * For every {@link Response}, additionally returns {@link Adjoints} which can
   * be used to update the score provided in the {@link Response}.
   */
  interface Trainable {
    List<Pair<Response, Adjoints>> search2(Query q);
    
    default Iterable<Response> search(Query q) {
      List<Pair<Response, Adjoints>> s = search2(q);
      List<Response> r = new ArrayList<>();
      for (Pair<Response, Adjoints> x : s)
        r.add(x.get1());
      return r;
    }
  }
  
  static class DummyTrainable implements Trainable {
    private String name;
    private IkbpSearch wrapped;
    public boolean verbose = true;
    
    public DummyTrainable(String name, IkbpSearch wrapped) {
      this.name = name;
      this.wrapped = wrapped;
    }

    @Override
    public List<Pair<Response, Adjoints>> search2(Query q) {
      List<Pair<Response, Adjoints>> r = new ArrayList<>();
      for (Response rr : wrapped.search(q)) {
        r.add(new Pair<>(rr, new Adjoints() {
          @Override public double forwards() {
            return 0;
          }
          @Override public void backwards(double dErr_dForwards) {
            if (verbose)
              Log.info("not applying update, name=" + name + " dErr_dForwards=" + dErr_dForwards);
          }
        }));
      }
      return r;
    }
    
  }
}
