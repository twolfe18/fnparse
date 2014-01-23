package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * given some simple fake data, can we run inference end to end
 * and produce sensical results?
 * @author travis
 */
public class SemaforicTest implements FrameInstanceProvider {

	private Frame nullFrame;
	private List<Frame> frames;
	private List<FrameInstance> frameInstances;
	private List<Sentence> sentences;
	
	public static void main(String[] args) {
		SemaforicTest st = new SemaforicTest();
		st.populateFrames();
		st.train();
	}
	
	@Override
	public List<Sentence> getFrameInstances() {
		if(sentences == null) {
			populateFrames();
			sentences = DataUtil.addFrameInstancesToSentences(frameInstances);
		}
		return sentences;
	}
	
	public void populateFrames() {
		
		nullFrame = Frame.nullFrame;
		Frame motion = new Frame(1, "MOTION", new LexicalUnit[] {
				new LexicalUnit("jumped", "V"), new LexicalUnit("throw", "V")},
				new String[] {"mover", "moved", "path"});
		Frame communication = new Frame(2, "COMMUNICATION", new LexicalUnit[] {
				new LexicalUnit("say", "V"), new LexicalUnit("speech", "N")},
				new String[] {"communicator", "communicatee", "message"});
		
		frames = new ArrayList<Frame>();
		frames.add(nullFrame);
		frames.add(motion);
		frames.add(communication);
		
		String ds = "test";
		Sentence s1 = new Sentence(ds, "s1", new String[] {"the", "fox", "jumped", "over", "there"}, new String[] {"d", "n", "v", "p", "n"});
		Sentence s2 = new Sentence(ds, "s2", new String[] {"john", "said", "java", "smells"}, new String[] {"n", "v", "n", "v"});
		Sentence s3 = new Sentence(ds, "s3", new String[] {"not", "all", "words", "trigger", "frames"}, new String[] {"q", "q", "n", "v", "n"});
		
		frameInstances = new ArrayList<FrameInstance>();
		
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(0), s1));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(1), s1));
		frameInstances.add(FrameInstance.newFrameInstance(motion, Span.widthOne(2), new Span[] {new Span(0, 2), new Span(0, 2), new Span(3, 5)}, s1));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(3), s1));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(4), s1));
		
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(0), s2));
		frameInstances.add(FrameInstance.newFrameInstance(communication, Span.widthOne(1), new Span[] {new Span(0, 1), Span.nullSpan, new Span(2, 4)}, s2));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(2), s2));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(3), s2));
		
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(0), s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(1), s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(2), s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(3), s3));
		frameInstances.add(FrameInstance.frameMention(nullFrame, Span.widthOne(4), s3));
	}

	public void train() {
		FGFNParser sem = new FGFNParser();
		sem.train(getFrameInstances());
	}

	@Override
	public String getName() { return "SemaforicTest"; }
}
