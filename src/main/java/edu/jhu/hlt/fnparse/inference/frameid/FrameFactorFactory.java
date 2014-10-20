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
import edu.jhu.hlt.fnparse.util.FeatureUtils;

/**
 * Instantiates factors that touch f_it.
 * 
 * @author travis
 */
public final class FrameFactorFactory implements FactorFactory<FrameVars> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(FrameFactorFactory.class);

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

    TemplateContext ctx = features.getContext();
    ctx.clear();
    List<Factor> factors = new ArrayList<Factor>();
    for (FrameVars fhyp : fr) {
      final int T = fhyp.numFrames();
      for (int tIdx = 0; tIdx < T; tIdx++) {
        Frame t = fhyp.getFrame(tIdx);
        Var frameVar = fhyp.getVariable(tIdx);
        int targetHead = params.getParserParams().headFinder.head(fhyp.getTarget(), s);
        ctx.setSentence(s);
        ctx.setFrame(t);
        ctx.setTarget(fhyp.getTarget());
        ExplicitExpFamFactor phi;
        if (l != null) {  // Latent syntax
          Var linkVar = l.getLinkVar(-1, targetHead);
          VarSet vs = new VarSet(linkVar, frameVar);
          phi = new ExplicitExpFamFactor(vs);
          //LOG.debug("instantiating binary factor (latent deps) for " + vs);
          for (int config = 0; config < 4; config++) {
            VarConfig vc = vs.getVarConfig(config);
            boolean link = BinaryVarUtil.configToBool(vc.getState(linkVar));
            boolean frame = BinaryVarUtil.configToBool(vc.getState(frameVar));
            if (frame) {
              ctx.setHead1_isRoot(link);
              FeatureVector fv = new FeatureVector();
              features.featurize(fv);
              phi.setFeatures(config, fv);
            } else {
              phi.setFeatures(config, FeatureUtils.emptyFeatures);
            }
          }
        } else {          // No latent syntax
          VarSet vs = new VarSet(frameVar);
          phi = new ExplicitExpFamFactor(vs);
          if (params.getParserParams().useSyntaxFeatures) {
            //LOG.debug("instantiating unary factor using syntax for " + vs);
            int head = s.getCollapsedDeps().getHead(targetHead);
            ctx.setTargetHead(head);
          } else {
            //LOG.debug("instantiating unary factor for " + vs);
            ctx.setTargetHead(TemplateContext.UNSET);
          }
          FeatureVector fv = new FeatureVector();
          features.featurize(fv);
          phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
          phi.setFeatures(BinaryVarUtil.boolToConfig(false),
              FeatureUtils.emptyFeatures);
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

