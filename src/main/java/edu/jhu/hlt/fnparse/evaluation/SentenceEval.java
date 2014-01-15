package edu.jhu.hlt.fnparse.evaluation;

import java.util.List;

import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;


public class SentenceEval {

	private Sentence sentence;
	private List<FrameInstance> gold;
	private List<FrameInstance> hyp;
	
	public SentenceEval(Sentence s, List<FrameInstance> gold, List<FrameInstance> hyp) {
		this.sentence = s;
		this.gold = gold;
		this.hyp = hyp;
	}
	
	// TODO add some methods, maybe call FrameInstanceEval
	
}
