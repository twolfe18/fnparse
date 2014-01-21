package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * given some simple fake data, can we run inference end to end
 * and produce sensical results?
 * @author travis
 */
public class SemaforicTests {

	private Frame nullFrame;
	private List<Frame> frames;
	private List<FrameInstance> frameInstances;
	
	public static void main(String[] args) {
		SemaforicTests st = new SemaforicTests();
		st.populateFrames();
		st.train();
	}
	
	public void populateFrames() {
		
		nullFrame = Frame.nullFrame;
		Frame motion = new Frame(1, "MOTION", new String[] {"jumped.v", "throw.v"}, new String[] {"mover", "moved", "path"});
		Frame communication = new Frame(2, "COMMUNICATION", new String[] {"say.v", "speech.n"}, new String[] {"communicator", "communicatee", "message"});
		
		frames = new ArrayList<Frame>();
		frames.add(nullFrame);
		frames.add(motion);
		frames.add(communication);
		
		String ds = "test";
		Sentence s1 = new Sentence(ds, "s1", new String[] {"the", "fox", "jumped", "over", "there"}, new String[] {"d", "n", "v", "p", "n"});
		Sentence s2 = new Sentence(ds, "s2", new String[] {"john", "said", "java", "smells"}, new String[] {"n", "v", "n", "v"});
		Sentence s3 = new Sentence(ds, "s3", new String[] {"not", "all", "words", "trigger", "frames"}, new String[] {"q", "q", "n", "v", "n"});
		
		frameInstances = new ArrayList<FrameInstance>();
		
		frameInstances.add(FrameInstance.frameMention(nullFrame, 0, s1));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 1, s1));
		frameInstances.add(FrameInstance.newFrameInstance(motion, 2, new Span[] {new Span(0, 2), new Span(0, 2), new Span(3, 5)}, s1));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 3, s1));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 4, s1));
		
		frameInstances.add(FrameInstance.frameMention(nullFrame, 0, s2));
		frameInstances.add(FrameInstance.newFrameInstance(communication, 1, new Span[] {new Span(0, 1), Span.nullSpan, new Span(2, 4)}, s2));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 2, s2));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 3, s2));
		
		frameInstances.add(FrameInstance.frameMention(nullFrame, 0, s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 1, s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 2, s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 3, s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, 4, s3));
	}
	
	// group by sentence
	public Map<Sentence, List<FrameInstance>> getExamples() {
		Map<Sentence, List<FrameInstance>> m = new HashMap<Sentence, List<FrameInstance>>();
		for(FrameInstance fi : frameInstances) {
			List<FrameInstance> fis = m.get(fi.getSentence());
			if(fis == null) fis = new ArrayList<FrameInstance>();
			fis.add(fi);
			m.put(fi.getSentence(), fis);
		}
		return m;
	}
	
	public void train() {
		Semaforic sem = new Semaforic();
		sem.train(getExamples(), frames, nullFrame);
	}
}
