package edu.jhu.hlt.ikbp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.ikbp.features.MentionFeatureExtractor;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.tutils.scoring.BilinearModel;
import edu.jhu.hlt.tutils.scoring.BilinearModel.MissingFeatureException;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

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
  interface Trainable extends IkbpSearch {
    List<Pair<Response, Adjoints>> search2(Query q);
    
    default Iterable<Response> search(Query q) {
      List<Pair<Response, Adjoints>> s = search2(q);
      List<Response> r = new ArrayList<>();
      for (Pair<Response, Adjoints> x : s)
        r.add(x.get1());
      return r;
    }
  }
  
  public static class DummyTrainable implements Trainable {
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
        r.add(new Pair<>(rr, Adjoints.Constant.ONE));
//        r.add(new Pair<>(rr, new Adjoints() {
//          @Override
//          public double forwards() {
//            return 1;
//          }
//          @Override
//          public void backwards(double dErr_dForwards) {
//            if (verbose)
//              Log.info("not applying update, name=" + name + " dErr_dForwards=" + dErr_dForwards);
//          }
//        }));
      }
      return r;
    }
    
  }
  
  /**
   * This should be implemented SEPARATELY from the wrapped {@link IkbpSearch}
   * so that they can be swapped out easily. You may want coordination between
   * {@link IkbpSearch} and this class (along the lines of Tongfei's
   * discriminative retrieval for IR), but that is the special case not the
   * general one.
   * 
   * TODO This class assumes feature extraction is already done, and stored
   * in the {@link Node}. Figure out where to do this extraction.
   */
  public static class FeatureBased implements Trainable {
    private IkbpSearch wrapped;
    private Alphabet<String> alph;  // TODO
    private BilinearModel model;
    private MentionFeatureExtractor mfe;
    
    public boolean debug = true;
    
    public FeatureBased(IkbpSearch wrapped, MentionFeatureExtractor features, Random rand) {
      if (wrapped == null || features == null)
        throw new IllegalArgumentException();
      this.wrapped = wrapped;
      this.alph = new Alphabet<>();
      this.mfe = features;

      int numTypes = FeatureType.values().length;
      this.model = new BilinearModel(numTypes);
      this.model.randInitEmbWeights(rand, 0.01);
      
      int D = 50_000;
      int K = 64;
      this.model.addFeature(FeatureType.INTERCEPT.ordinal(), D, K);
      this.model.addFeature(FeatureType.HEADWORD.ordinal(), D, K);
      this.model.addFeature(FeatureType.REGULAR.ordinal(), D, K);
      this.model.addInteraction(
          FeatureType.HEADWORD.ordinal(),
          FeatureType.HEADWORD.ordinal(),
          BilinearModel.Mode.SCALAR);
      this.model.addInteraction(
          FeatureType.REGULAR.ordinal(),
          FeatureType.REGULAR.ordinal(),
          BilinearModel.Mode.SCALAR);
      this.model.addInteraction(
          FeatureType.REGULAR.ordinal(),
          FeatureType.INTERCEPT.ordinal(),
          BilinearModel.Mode.SCALAR);
      this.model.addInteraction(
          FeatureType.INTERCEPT.ordinal(),
          FeatureType.REGULAR.ordinal(),
          BilinearModel.Mode.SCALAR);
      this.model.addInteraction(
          FeatureType.HEADWORD.ordinal(),
          FeatureType.INTERCEPT.ordinal(),
          BilinearModel.Mode.SCALAR);
      this.model.addInteraction(
          FeatureType.INTERCEPT.ordinal(),
          FeatureType.HEADWORD.ordinal(),
          BilinearModel.Mode.SCALAR);
    }
    
    public List<BilinearModel.ProjFeats> encode(Node n) {

      if (debug)
        System.out.println("in:  " + DataUtil.showFeatures(n.getFeatures()));

      // Extract features on this mention
      List<String> m_id = DataUtil.getMentions(n);
      for (String m : m_id)
        mfe.extract(m, n.getFeatures());
      
      boolean reindex = true;
      List<BilinearModel.ProjFeats> f = new ArrayList<>();
      f.add(model.score(FeatureType.INTERCEPT.ordinal(), new int[] {0}, reindex));
      
      // Sort feature by type
      Collections.sort(n.getFeatures(), new Comparator<Id>() {
        @Override
        public int compare(Id o1, Id o2) {
          return o1.getType() - o2.getType();
        }
      });
      
      // Group features by type
      int end;
      int s = n.getFeaturesSize();
      for (int cur = 0; cur < s; ) {
        
        // Find the features that have the same type as cur
        int t = n.getFeatures().get(cur).getType();
        for (end = cur + 1; end < s; end++) {
          int tt = n.getFeatures().get(end).getType();
          if (t != tt)
            break;
        }

        // New (type:int, fx:int[])
        int w = end - cur;
        int[] fx = new int[w];
        for (int i = 0; i < w; i++)
          fx[i] = n.getFeatures().get(cur + i).getId();
        if (debug)
          Log.info("type=" + FeatureType.findByValue(t) + " fx=" + Arrays.toString(fx));
        BilinearModel.ProjFeats pf = model.score(t, fx, reindex);
        if (pf != null) {
          f.add(pf);
        } else if (debug) {
          Log.info("skipping " + FeatureType.findByValue(t));
        }

        cur = end;
      }

//      for (Id feature : n.getFeatures()) {
//        if (feature.getType() == FeatureType.MENTION_ID.ordinal()) {
//          // these features have values which are pointers, tell you no information you can generalize on.
//          continue;
//        }
//
//        System.out.println("out: " + DataUtil.showFeatures(Arrays.asList(feature)));
//        if (seenTypes.get(feature.getType()))
//          System.out.println("skipping: " + feature);
//        seenTypes.set(feature.getType());
//
//        // Use the feature hash for now
//        int[] fx = new int[] { feature.getId() };
//        BilinearModel.ProjFeats pf = model.score(feature.getType(), fx, reindex);
//        if (pf != null)
//          f.add(pf);
//      }

      if (debug)
        Log.info("numFeats=" + f.size());
      return f;
    }

    @Override
    public List<Pair<Response, Adjoints>> search2(Query q) {
      List<Pair<Response, Adjoints>> b = new ArrayList<>();
      Iterable<Response> base = wrapped.search(q);
      
      List<BilinearModel.ProjFeats> fy = encode(q.getSubject());
      
      for (Response r : base) {

//        List<String> f = score(q, r);
//        int[] fi = new int[f.size()];
//        for (int i = 0; i < fi.length; i++)
//          fi[i] = alph.lookupIndex(f.get(i));
//        boolean reindex = true;
//        Adjoints a = theta.score(fi, reindex);
        
        // Assumption 0: The score of a response only depends on the center (thing aligned with the subject).
        // We will relax this by looking at the full alignment between PKB and new doc later.
        Node center = DataUtil.lookup(r.getAnchor(), r.getDelta().getNodes());
        List<BilinearModel.ProjFeats> fx = encode(center);
        try {
          Adjoints a = model.score(fy, fx);
          r.setScore(a.forwards());
          b.add(new Pair<>(r, a));
        } catch (MissingFeatureException m) {
          throw new RuntimeException("couldn't find feature: " + FeatureType.values()[m.type]);
        }
      }
      return b;
    }
  }
}
