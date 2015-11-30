package edu.jhu.hlt.fnparse.inference.stages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;

//import edu.jhu.gm.data.LabeledFgExample;
//import edu.jhu.gm.inf.FgInferencerFactory;
//import edu.jhu.gm.inf.FgInferencer;
//import edu.jhu.gm.model.Factor;
//import edu.jhu.gm.model.FactorGraph;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
//import edu.jhu.hlt.fnparse.util.HasFactorGraph;
import edu.jhu.hlt.fnparse.util.HasFgModel;

/**
 * For big factor graphs, the bottleneck is often feature extraction.
 * A globally normalized graph is not desirable in this case because you
 * will end up normalizing large parts of the sample space which you will
 * never consider. A locally normalized model can improve on this because
 * you may be able to avoid ever computing a normalizing constant for large
 * (numbers of) cliques in the graph.
 * 
 * Consider FrameNet parsing as an example: we want to first decode the frame
 * being evoked, and then choose its roles (assuming that we're not doing joint
 * prediction -- which is of questionable value in this case). We do not want to
 * extract features for role variables that relate to a frame *that we didn't decode*.
 * ^^is a bad example, because we already have code that does these in a pipeline,
 *   the example i really need is [r_itjk before r_itjk^e]. if we choose r_itjk=0,
 *   then all the time spent extracting features for r_itjk^e was wasted.
 * 
 * @author travis
 */
public interface Stage<Input, Output> extends HasFgModel {

  public String getName();

  /** Take some command line arguments and update/apply them */
  public void configure(Map<String, String> configuration);

  /**
   * Write out a model as pairs of feature names and weights. Do not write out
   * anything using integer feature indexes or alphabets.
   */
  public void saveModel(DataOutputStream dos, GlobalParameters globals);

  /**
   * Read in the same format that is written out by saveModel.
   */
  public void loadModel(DataInputStream dis, GlobalParameters globals);

  public void scanFeatures(
      List<? extends Input> unlabeledExamples,
      List<? extends Output> labels,
      double maxTimeInMinutes,
      int maxFeaturesAdded);

  public void scanFeatures(List<FNParse> data);

  /**
   * Should include any tuning steps that are necessary (implementer of this
   * method should split off dev/tune data)
   */
  public void train(List<Input> x, List<Output> y);

  /**
   * Create the FactorGraph and other materials needed for prediction.
   * Technically, {@link Decodable} is lazy, so all that this is guaranteed to
   * do is instantiate variables and factors and compute features.
   * 
   * @param input
   * @param output may be null, in which case "unlabeled" StageData should be
   *        returned (only capable of decoding), otherwise labeled StageData
   *        should be returned (which is suitable for training).
   */
  public StageDatumExampleList<Input, Output> setupInference(
      List<? extends Input> input, List<? extends Output> output);


//  /**
//   * Basically a Future<Output>, but stores marginals, so you can
//   * decode many times without running inference more than once.
//   */
//  public static abstract class Decodable<Output>
//      implements IDecodable<Output>, HasFactorGraph, HasFgModel {
//    public final FactorGraph fg;
//    public final FgInferencerFactory infFact;
//    //public final HasFgModel hasModel;
//    private FgInferencer inf;
//
//    public Decodable(
//        FactorGraph fg,
//        FgInferencerFactory infFact) {
//      this.fg = fg;
//      this.infFact = infFact;
//      //this.hasModel = weights;
//    }
//
//    @Override
//    public FactorGraph getFactorGraph() { return fg; }
//
//    /**
//     * Ensures that inference has been run and the result has been cached.
//     */
//    public void force() {
//      getMargins();
//    }
//
//    /**
//     * Forces inference to be run, but will only do so once
//     * (future calls are just returned from cache).
//     */
//    public FgInferencer getMargins() {
//      if(inf == null) {
//        // We need to compute the scores for ExpFamFactors at some point
//        // and way I can think to choose where that should happen is
//        // that it should happen as late as possible, which is here.
//        for(Factor f : fg.getFactors())
//          f.updateFromModel(getWeights());
//        inf = infFact.getInferencer(fg);
//        inf.run();
//      }
//      return inf;
//    }
//
//    /** Should call getMargins() */
//    @Override
//    public abstract Output decode();
//  }
}
