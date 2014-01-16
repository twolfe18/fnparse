package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleFactory;
import edu.jhu.gm.feat.FactorTemplateList;
import edu.jhu.hlt.fnparse.features.TargetFeature;
import edu.jhu.hlt.fnparse.util.Configuration;
import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.FrameInstance;

class TravisFgExampleFactory implements FgExampleFactory {

	private List<FrameInstanceWithInferenceMaterials> examples;
	private List<String> frameNames;
	
	public TravisFgExampleFactory(List<FrameInstance> examples, TargetFeature targetFeatures, Configuration conf) {

		// domain of each var is the set of all Frames
		frameNames = new ArrayList<String>();
		frameNames.add("F0:" + conf.getFrameIndex().nullFrame.getName());
		int d = 1;
		for(Frame f : conf.getFrameIndex().allFrames()) {
			String n = String.format("F%d:%s", d, f.getName());
			frameNames.add(n);
			d += 1;
		}

		this.examples = new ArrayList<FrameInstanceWithInferenceMaterials>();
		for(FrameInstance fi : examples)
			this.examples.add(new FrameInstanceWithInferenceMaterials(fi, frameNames, targetFeatures, conf));
	}
	
	@Override
	public FgExample get(int i, FactorTemplateList fts) {
		FrameInstanceWithInferenceMaterials fi = examples.get(i);
		return new FgExample(fi.getFactorGraph(), fi.getGoldConfig(), fi, fts);
	}

	@Override
	public int size() { return examples.size(); }
}