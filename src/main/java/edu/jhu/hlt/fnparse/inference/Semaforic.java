package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import travis.Vector;
import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleFactory;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.FgExampleListBuilder;
import edu.jhu.gm.feat.FactorTemplateList;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.feat.ObsFeatureExtractor;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.features.BasicTargetFeatures;
import edu.jhu.hlt.fnparse.features.TargetFeature;
import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;
import edu.jhu.hlt.fnparse.util.Span;


public class Semaforic implements FrameNetParser {

	private Random rand;
	private Vector weights;
	
	// TODO write code to flatten many features into one feature
	private TargetFeature targetFeatures = new BasicTargetFeatures();
	
	// used for dummy prediction
	private Frame dummyFrame = new Frame(1, "dummy-frame",
		new String[] {"mock.v", "imitate.v"}, new String[] {"Agent", "Patient"});
	
	public Semaforic(int featureDimension) {
		rand = new Random(9001);
		weights = Vector.dense(featureDimension);
	}
	
	@Override
	public List<FrameInstance> parse(Sentence s) {
		
		// predict targets
		List<FrameInstance> targets = targetIdentification(s);
		
		// predict argument structure
		for(FrameInstance t : targets)
			predictArguments(t);
		
		return targets;
	}

	/**
	 * creates FrameInstances with no arguments labeled
	 */
	public List<FrameInstance> targetIdentification(Sentence s) {
		
		// TODO update this to use Matt's FactorGraph code
		
		List<FrameInstance> ts = new ArrayList<FrameInstance>();
		int n = s.size();
		for(int i=0; i<n; i++) {
			Vector features = targetFeatures.getFeatures(dummyFrame, i, s);
			double score = weights.dot(features);
			if(score > 0d) {
				Span[] args = new Span[dummyFrame.numRoles()];
				FrameInstance fi = new FrameInstance(dummyFrame, i, args, s);
				ts.add(fi);
			}
		}
		return ts;
	}
	
	/**
	 * sets the argument spans in the given FrameInstance
	 */
	public void predictArguments(FrameInstance f) {
		int sentLen = f.getSentence().size();
		int n = f.getFrame().numRoles();
		for(int i=0; i<n; i++) {
			// TODO replace with real classifiers, beam search for overlap constraints
			// for now, choose a random word
			int ai = rand.nextInt(sentLen);
			f.setArgument(i, new Span(ai, ai+1));
		}
	}
	
	@Override
	public void train(List<FrameInstance> examples) {
		trainTargetIdentification(examples);
		trainArgumentIdentification(examples);
	}
	
	static class FrameInstanceWithInferenceMaterials implements ObsFeatureExtractor {
		
		private FrameInstance fi;
		private FactorGraph fg;
		private VarConfig goldConf;
		private List<FeatureVector> features;
		private TargetFeature targetFeatures;
		
		public FrameInstanceWithInferenceMaterials(FrameInstance fi, List<String> frameNames, TargetFeature targetFeatures) {
			
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
				goldConf.put(t, fi.getFrame().getId());
			}
		}
		
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
			
			int n = sentenceLength();
			for(int i=0; i<n; i++) {
				Vector full = Vector.sparse(true);
				for(Frame f : Frame.allFrames()) {
					Vector ff = targetFeatures.getFeatures(f, i, fi.getSentence());
					// full += ff with offset
				}
				FeatureVector fv = null;	// (FeatureVector) full;	// TOOD real conversion
				features.add(fv);
			}
		}

		@Override
		public void clear() { features = null; }
	}
	
	static class TravisFgExampleFactory implements FgExampleFactory {

		private List<FrameInstanceWithInferenceMaterials> examples;
		private List<String> frameNames;
		
		public TravisFgExampleFactory(List<FrameInstance> examples, TargetFeature targetFeatures) {

			// domain of each var is the set of all Frames
			frameNames = new ArrayList<String>();
			frameNames.add("F0:" + Frame.NULL_FRAME.getName());
			int d = 1;
			for(Frame f : Frame.allFrames()) {
				String n = String.format("F%d:%s", d, f.getName());
				frameNames.add(n);
				d += 1;
			}

			this.examples = new ArrayList<FrameInstanceWithInferenceMaterials>();
			for(FrameInstance fi : examples)
				this.examples.add(new FrameInstanceWithInferenceMaterials(fi, frameNames, targetFeatures));
		}
		
		@Override
		public FgExample get(int i, FactorTemplateList fts) {
			FrameInstanceWithInferenceMaterials fi = examples.get(i);
			return new FgExample(fi.getFactorGraph(), fi.getGoldConfig(), fi, fts);
		}

		@Override
		public int size() { return examples.size(); }
	}
	
	public void trainTargetIdentification(List<FrameInstance> examples) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FactorTemplateList fts = null;	// TODO how?
		FgExampleFactory exampleFactory = new TravisFgExampleFactory(examples, targetFeatures);
		FgExampleListBuilder dataBuilder = null;	// TODO how?
		FgExampleList data = dataBuilder.getInstance(fts, exampleFactory);
		
		FgModel model = null;
		trainer.train(model, data);
		
		// TODO figure out how to get parameters out of FgModel
		// it doesn't seem like there is an obvious way (getter)
	}
	
	public void trainArgumentIdentification(List<FrameInstance> examples) {
		throw new RuntimeException("implement me");
	}
	
}


