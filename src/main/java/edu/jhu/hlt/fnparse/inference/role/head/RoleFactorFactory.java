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
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
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

  /**
   * @param params
   * @param factorMode says how the (r_itjk ~ l_ij) factor should be parameterized
   */
  public RoleFactorFactory(ParserParams params, BinaryBinaryFactorHelper.Mode factorMode) {
    this.params = params;
    this.features = new TemplatedFeatures("roleHeadId",
        params.getFeatureTemplateDescription(),
        params.getAlphabet());
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
        TemplateContext context = features.getContext();
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
          if (role || dep) {
            context.setHead2(rv.i);
            if (role) {
              context.setRole(rvar.k);
              context.setArgHead(rvar.j);
              context.setHead1(rvar.j);
            }
            if (dep) {
              context.setHead1_parent(rv.i);
            }
          }
          FeatureVector fv = new FeatureVector();
          if (SHOW_FEATURES) {
            String msg = String.format("[variables] rvar[%d,%d]=%s dep=%s",
                rvar.j, rvar.k, role, dep);
            features.featurizeDebug(fv, msg);
          } else {
            features.featurize(fv);
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
