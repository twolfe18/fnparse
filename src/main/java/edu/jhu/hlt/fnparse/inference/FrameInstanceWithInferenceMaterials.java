package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import travis.Vector;
import edu.jhu.gm.feat.FactorTemplate;
import edu.jhu.gm.feat.FactorTemplateList;
import edu.jhu.gm.feat.Feature;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.feat.ObsFeatureExtractor;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.features.TargetFeature;
import edu.jhu.hlt.fnparse.util.Configuration;
import edu.jhu.util.Alphabet;

class FrameInstanceWithInferenceMaterials implements ObsFeatureExtractor {
	
	private Configuration conf;
	private FrameInstance fi;
	private FactorGraph fg;
	private VarConfig goldConf;
	private List<FeatureVector> features;
	private TargetFeature targetFeatures;
	
	public FrameInstanceWithInferenceMaterials(FrameInstance fi, List<String> frameNames, TargetFeature targetFeatures, Configuration conf) {
		
		this.conf = conf;
		this.fi = fi;
		this.fg = new FactorGraph();
		this.goldConf = new VarConfig();
		this.targetFeatures = targetFeatures;
		
		// one variable per word
		// one unary factor per variable
		int n = sentenceLength();
		for(int i=0; i<n; i++) {
			String varName = "t"+i;
			Var t = new Var(VarType.PREDICTED, frameNames.size(), varName, frameNames);
			ExpFamFactor f = new ExpFamFactor(new VarSet(t), "targetModel");
			fg.addFactor(f);	// TODO don't need to add variables to FactorGraph?
			if(hasGoldConf())
				goldConf.put(t, fi.getFrame().getId());
		}
	}
	
	public boolean hasGoldConf() { return fi.getFrame() != null; }
	
	// one per word
	public int sentenceLength() { return fi.getSentence().size(); }
	
	public FrameInstance getFrameInstance() { return fi; }
	public FactorGraph getFactorGraph() { return fg; }
	public VarConfig getGoldConfig() { return goldConf; }

	@Override
	public FeatureVector calcObsFeatureVector(int factorId) {
		return features.get(factorId);
	}

	@Override
	public void init(FactorGraph fg, FactorGraph fgLat,
			FactorGraph fgLatPred, VarConfig goldConfig,
			FactorTemplateList fts) {
		
		// ==== COMPUTE FEATURES ====
		int n = sentenceLength();
		for(int i=0; i<n; i++) {
			Vector full = Vector.sparse(true);
			for(Frame f : conf.getFrameIndex().allFrames()) {
				Vector ff = targetFeatures.getFeatures(f, i, fi.getSentence());
				// full += ff with offset
			}
			FeatureVector fv = null;	// (FeatureVector) full;	// TOOD real conversion
			features.add(fv);
		}
		
		// ==== TELL FactorTemplateList WHAT THE FEATURES ARE ====
		Alphabet<Feature> alphabet = fts.getTemplateByKey("targetModel").getAlphabet();
		VarSet vars = null;
		fts.add(new FactorTemplate(vars, alphabet, "targetModel"));
		for(int i=0; i<this.targetFeatures.cardinality(); i++) {
			String featName = String.format("feat%d", i);
			alphabet.lookupIndex(new Feature(featName));
		}
	}

	@Override
	public void clear() { features = null; }
}

