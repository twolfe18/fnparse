package edu.jhu.hlt.uberts.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;

public abstract class FeatureExtractionFactor<T> {

  public abstract List<T> features(HypEdge yhat, Uberts x);


  public static class Weight<T> {
    int nObs = 0;
    double theta = 0;
    final T item;
    public Weight(T item) {
      this.item = item;
      this.nObs = 0;
      this.theta = 0;
    }
    public void increment(double amount) {
      theta += amount;
      nObs++;
    }
    @Override
    public String toString() {
      return String.format("(%s %+.2f n=%d)", item.toString(), theta, nObs);
    }
  }

  private Map<Relation, Map<T, Weight<T>>> theta = new HashMap<>();

  /**
   * Returns a non-caching Adjoints.
   */
  public Adjoints score(HypEdge yhat, Uberts x) {

    // Look up weight vector based on Relation of the given edge
    Map<T, Weight<T>> t = theta.get(yhat.getRelation());
    if (t == null) {
      t = new HashMap<>();
      theta.put(yhat.getRelation(), t);
    }
    final Map<T, Weight<T>> tt = t;

    // Extract features
    List<T> feats = features(yhat, x);

    // Build an Adjoints
    return new Adjoints() {
      private List<T> fx = feats;
      private Map<T, Weight<T>> theta = tt;
      @Override
      public double forwards() {
        double s = 0;
        for (T index : fx) {
          Weight<T> w = theta.get(index);
          if (w != null)
            s += w.theta;
        }
        return s;
      }
      @Override
      public void backwards(double dErr_dForwards) {
        for (T index : fx) {
          Weight<T> w = theta.get(index);
          if (w == null) {
            w = new Weight<>(index);
            theta.put(index, w);
          }
          w.increment(-dErr_dForwards);
        }
      }
      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(Adj");
        for (T index : fx) {
          Weight<T> w = theta.get(index);
          if (w == null)
            w = new Weight<>(index);
          sb.append(' ');
          sb.append(w.toString());
          if (sb.length() > 200) {
            sb.append("...");
            break;
          }
        }
        sb.append(')');
        return sb.toString();
      }
    };
  }


  public static class Simple extends FeatureExtractionFactor<String> {
    private List<FeatletIndex.Feature> features;

    public Simple(List<Relation> relevant, Uberts u) {
      NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
      FeatletIndex featlets = new FeatletIndex(tokenIndex, relevant);
      this.features = featlets.getFeatures();
    }

    public List<String> features(HypEdge yhat, Uberts x) {
      List<String> fx = new ArrayList<>();
      fx.add("INTERCEPT");
      int n = features.size();
      for (int i = 0; i < n; i++) {
        FeatletIndex.Feature f = features.get(i);
        List<String> ls = f.extract(yhat, x);
        for (String fs : ls)
          fx.add(f.getName() + "/" + fs);
      }
      return fx;
    }
  }
}

