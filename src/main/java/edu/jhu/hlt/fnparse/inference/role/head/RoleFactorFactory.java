package edu.jhu.hlt.fnparse.inference.role.head;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadVars.RVar;

/**
 * should really be called RoleIdFactorFactory (this is not a FactorFactory for RoleSpanStage)
 * 
 * @author travis
 */
public final class RoleFactorFactory implements FactorFactory<RoleHeadVars> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(RoleFactorFactory.class);
  public static boolean SHOW_FEATURES = false;

  private TemplatedFeatures features;
  public FgModel weights;
  public final ParserParams params;

  public boolean allowSpanFeatures = true;

  public RoleFactorFactory(ParserParams params) {
    this.params = params;
  }

  public TemplatedFeatures getTFeatures() {
    if (features == null) {
      features = new TemplatedFeatures("roleHeadId",
          params.getFeatureTemplateDescription(),
          params.getAlphabet());
    }
    return features;
  }

  private void debugMsg(
      Sentence s,
      List<RoleHeadVars> fr,
      ProjDepTreeFactor l,
      ConstituencyTreeFactor c) {
    int count = 0;
    for (RoleHeadVars rv : fr)
      for (Iterator<RVar> it = rv.getVars(); it.hasNext(); it.next())
        count++;
    LOG.info("[debugMsg] about to featurize " + count + " things");
  }

  /**
   * Instantiates the following factors:
   * r_itjk ~ 1
   * r_itjk ~ l_ij
   */
  @Override
  public List<Factor> initFactorsFor(
      Sentence s,
      List<RoleHeadVars> fr,
      ProjDepTreeFactor l,
      ConstituencyTreeFactor c) {
    if (SHOW_FEATURES)
      debugMsg(s, fr, l, c);
    List<Factor> factors = new ArrayList<Factor>();
    for (RoleHeadVars rv : fr) {
      Iterator<RVar> it = rv.getVars();
      while (it.hasNext()) {
        RVar rvar = it.next();
        LinkVar link = null;
        boolean argRealized = rvar.j < s.size();
        assert rvar.j >= 0;
        VarSet vs;
        if (argRealized && params.useLatentDepenencies) {
          link = l.getLinkVar(rv.i, rvar.j);
          if (link == null) {
            assert rv.i == rvar.j;
            vs = new VarSet(rvar.roleVar);
          } else {
            vs = new VarSet(rvar.roleVar, link);
          }
        } else {
          vs = new VarSet(rvar.roleVar);
        }
        ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
        TemplatedFeatures feats = getTFeatures();
        TemplateContext context = feats.getContext();
        int n = vs.calcNumConfigs();
        for (int i = 0; i < n; i++) {
          VarConfig vc = vs.getVarConfig(i);
          boolean role = argRealized && BinaryVarUtil.configToBool(vc.getState(rvar.roleVar));
          boolean dep = link != null && BinaryVarUtil.configToBool(vc.getState(link));
          context.clear();
          context.setStage(RoleHeadStage.class);
          context.setSentence(s);
          context.setFrame(rv.t);
          context.setTargetHead(rv.i);
          if (allowSpanFeatures) {
            context.setTarget(Span.widthOne(rv.i));
            context.setSpan2(Span.widthOne(rv.i));
          }
          if (role || dep) {
            context.setHead2(rv.i);
            if (allowSpanFeatures)
              context.setSpan2(Span.widthOne(rv.i));
            if (role) {
              context.setRole(rvar.k);
              context.setArgHead(rvar.j);
              context.setHead1(rvar.j);
              if (allowSpanFeatures) {
                context.setSpan1(Span.widthOne(rvar.j));
                context.setArg(Span.widthOne(rvar.j));
              }
            }
            if (dep) {
              context.setHead1_parent(rv.i);
            }
          }
          context.blankOutIllegalInfo(params);
          FeatureVector fv = new FeatureVector();
          if (SHOW_FEATURES) {
            String msg = String.format("[variables] rvar[%d,%d]=%s dep=%s",
                rvar.j, rvar.k, role, dep);
            feats.featurizeDebug(fv, msg);
          } else {
            feats.featurize(fv);
          }
          phi.setFeatures(i, fv);
        }
        factors.add(phi);
      }
    }
    return factors;
  }

  @Override
  public List<Features> getFeatures() {
    throw new RuntimeException("remove this method!");
  }
}
