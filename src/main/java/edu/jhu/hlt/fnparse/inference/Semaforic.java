package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;
import edu.jhu.hlt.fnparse.util.Span;


public class Semaforic implements FrameNetParser {

	private Random rand;
	private double[] weights;
	private Frame dummyFrame = new Frame("dummy-frame",
		new String[] {"mock.v", "imitate.v"}, new String[] {"Agent", "Patient"});
	
	public Semaforic(int featureDimension) {
		rand = new Random(9001);
		weights = new double[featureDimension];
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
		List<FrameInstance> ts = new ArrayList<FrameInstance>();
		int n = s.size();
		for(int i=0; i<n; i++) {
			// TODO replace with classifier code!
			if("V".equalsIgnoreCase(s.getPos(i))) {
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
	
	public void trainTargetIdentification(List<FrameInstance> examples) {
		// setup a factor graph with only frame trigger nodes
		
	}
	
	public void trainArgumentIdentification(List<FrameInstance> examples) {
		throw new RuntimeException("implement me");
	}
	
}


