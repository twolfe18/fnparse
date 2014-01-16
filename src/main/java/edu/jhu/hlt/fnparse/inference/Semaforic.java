package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleFactory;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.FgExampleListBuilder;
import edu.jhu.gm.data.FgExampleListBuilder.CacheType;
import edu.jhu.gm.data.FgExampleListBuilder.FgExamplesBuilderPrm;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.feat.FactorTemplateList;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.features.BasicTargetFeatures;
import edu.jhu.hlt.fnparse.features.TargetFeature;
import edu.jhu.hlt.fnparse.util.Configuration;
import edu.jhu.hlt.fnparse.util.DefaultConfiguration;
import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;
import edu.jhu.hlt.fnparse.util.Span;


public class Semaforic implements FrameNetParser {

	private Random rand = new Random(9001);
	private Configuration conf = new DefaultConfiguration();
	
	private FactorTemplateList fts = new FactorTemplateList();	// holds factor cliques, just says that there is one factor
	private FgModel targetModel;
	
	// TODO make this work
	private FgModel argumentModel;
	
	// TODO write code to flatten many features into one feature
	private TargetFeature targetFeatures = new BasicTargetFeatures(conf);
	
	// corresponds to the variables in the targetModel's factor graph
	private List<String> frameNames;
	
	@Override
	public List<FrameInstance> parse(Sentence s) {
		
		// predict targets
		List<FrameInstance> targets = predictTargets(s);
		
		// predict argument structure
		for(FrameInstance t : targets)
			predictArguments(t);
		
		return targets;
	}

	/**
	 * creates FrameInstances with no arguments labeled
	 */
	public List<FrameInstance> predictTargets(Sentence s) {
		List<FrameInstance> ts = new ArrayList<FrameInstance>();
		int n = s.size();
		for(int targetIdx=0; targetIdx<n; targetIdx++) {
			FrameInstance fi = new FrameInstance(null, targetIdx, null, s);
			FrameInstanceWithInferenceMaterials fiwim =
				new FrameInstanceWithInferenceMaterials(fi, frameNames, targetFeatures, conf);
			MbrDecoderPrm mbrDecPrm = new MbrDecoderPrm();
			MbrDecoder decoder = new MbrDecoder(mbrDecPrm);
			VarConfig goldConf = null;	// ?
			FgExample fge = new FgExample(fiwim.getFactorGraph(), goldConf, fiwim, fts);
			decoder.decode(targetModel, fge);
			VarConfig hyp = decoder.getMbrVarConfig();
			int frameId = hyp.getConfigIndex();
			if(frameId != conf.getFrameIndex().nullFrame.getId()) {
				Frame hypFrame = conf.getFrameIndex().getFrame(frameId);
				Span[] arguments = new Span[hypFrame.numRoles()];
				ts.add(new FrameInstance(hypFrame, targetIdx, arguments, s));
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
	
	public void trainTargetIdentification(List<FrameInstance> examples) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleFactory exampleFactory = new TravisFgExampleFactory(examples, targetFeatures, conf);
		FgExamplesBuilderPrm prm = new FgExamplesBuilderPrm();
		prm.cacheType = CacheType.MEMORY_STORE;
		FgExampleListBuilder dataBuilder = new FgExampleListBuilder(prm);
		FgExampleList data = dataBuilder.getInstance(fts, exampleFactory);
		
		boolean includeUnsupportedFeatures = true;
		targetModel = new FgModel(data, includeUnsupportedFeatures);
		targetModel = trainer.train(targetModel, data);
	}
	
	public void trainArgumentIdentification(List<FrameInstance> examples) {
		System.err.println("NOT TRAINTING ARGUMENT ID MODEL");
	}
	
}


