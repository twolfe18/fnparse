package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.HasParserParams;

/**
 * Instantiates factors that touch f_it.
 * 
 * @author travis
 */
public final class FrameFactorFactory implements FactorFactory<FrameVars> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(FrameFactorFactory.class);
  public static boolean SHOW_FEATURES = false;

  private TemplatedFeatures features;
  private HasParserParams params;

  public FrameFactorFactory(HasParserParams params) {
    this.params = params;
    this.features = new TemplatedFeatures("frameId",
        params.getParserParams().getFeatureTemplateDescription(),
        params.getParserParams().getAlphabet());
  }

  // TODO need to add an Exactly1 factor to each FrameVars
  // ^^^^ do i really need this if i'm not doing joint inference?
  @Override
  public List<Factor> initFactorsFor(
      Sentence s,
      List<FrameVars> fr,
      ProjDepTreeFactor l,
      ConstituencyTreeFactor c) {

    final int ROOT = -1;
    TemplateContext context = features.getContext();
    List<Factor> factors = new ArrayList<Factor>();
    for (FrameVars fhyp : fr) {
      final int T = fhyp.numFrames();
      int targetHead = params.getParserParams().headFinder.head(
          fhyp.getTarget(), s);
      context.clear();
      context.setStage(FrameIdStage.class);
      context.setSentence(s);
      context.setTarget(fhyp.getTarget());
      context.setSpan1(fhyp.getTarget());
      context.setTargetHead(targetHead);
      context.setHead1(targetHead);
      for (int tIdx = 0; tIdx < T; tIdx++) {
        Frame t = fhyp.getFrame(tIdx);
        Var frameVar = fhyp.getVariable(tIdx);
        ExplicitExpFamFactor phi;
        if (l != null) {  // Latent syntax
          Var linkVar = l.getLinkVar(-1, targetHead);
          VarSet vs = new VarSet(linkVar, frameVar);
          phi = new ExplicitExpFamFactor(vs);
          for (int config = 0; config < 4; config++) {
            VarConfig vc = vs.getVarConfig(config);
            boolean link = BinaryVarUtil.configToBool(vc.getState(linkVar));
            boolean frame = BinaryVarUtil.configToBool(vc.getState(frameVar));
            context.setHead1_parent(link ? ROOT : TemplateContext.UNSET);
            context.setFrame(frame ? t : null);
            FeatureVector fv = new FeatureVector();
            if (SHOW_FEATURES) {
              String msg = "[variables] " + vs.getVarConfig(config);
              features.featurizeDebug(fv, msg);
            } else {
              features.featurize(fv);
            }
            phi.setFeatures(config, fv);
          }
        } else {          // No latent syntax
          VarSet vs = new VarSet(frameVar);
          phi = new ExplicitExpFamFactor(vs);
          FeatureVector fv1 = new FeatureVector();
          context.setFrame(t);
          if (SHOW_FEATURES) {
            features.featurizeDebug(fv1, "[variables] (contained in context)");
          } else {
            features.featurize(fv1);
          }
          phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv1);
          FeatureVector fv2 = new FeatureVector();
          context.setFrame(null);
          if (SHOW_FEATURES) {
            features.featurizeDebug(fv2, "[variables] (contained in context)");
          } else {
            features.featurize(fv2);
          }
          phi.setFeatures(BinaryVarUtil.boolToConfig(false), fv2);
        }
        factors.add(phi);
      }
    }
    return factors;
  }

  @Override
  public List<Features> getFeatures() {
    return Arrays.asList((Features) features);
  }
}

