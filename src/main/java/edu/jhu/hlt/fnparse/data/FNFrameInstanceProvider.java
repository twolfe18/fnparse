package edu.jhu.hlt.fnparse.data;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.ArrayUtils;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class FNFrameInstanceProvider implements FrameInstanceProvider, Iterable<FNParse> {

	private LineIterator litrFrames;
	private LineIterator litrConll;
	private List<FNParse> fnpl = new LinkedList<FNParse>();
	private Map<String, Frame> frameByName;

	String curLineFramesFile;
	String curSentIdFrames;
	// The following 2 only occur in frames file so need to add Frames suffix
	String curAnnoSetId;
	String prevAnnoSetId;

	String prevFrameName;
	String prevTargetCharStart;
	String prevTargetCharEnd;

	String curLineConllFile;
	String curSentIdConll;	

	String prevSentId;

	@Override
	public String getName() { return "FrameNet_frame_instances"; }

	public FNFrameInstanceProvider(){
		// Right now hard code to return the train instances. Deal with initialization later.
		try {
			litrFrames = new LineIterator(new FileReader(UsefulConstants.TrainFN15FullTextFramesPath));
			litrConll = new LineIterator(new FileReader(UsefulConstants.TrainFN15FullTextConllPath));
			frameByName = FrameIndex.getInstance().nameToFrameMap();
			// The frame preamble is the first line
			// These line have been run for side effect
			litrFrames.nextLine();
			litrConll.nextLine();

			curLineConllFile=litrConll.nextLine();
			curLineFramesFile=litrFrames.nextLine();
			String[] cf = curLineFramesFile.split("\t");
			curSentIdFrames = cf[2];
			prevAnnoSetId = curAnnoSetId = cf[3];

			prevFrameName = cf[5];
			prevTargetCharStart = cf[7];
			prevTargetCharEnd = cf[8];

			String[] ff = curLineConllFile.split("\t");
			curSentIdConll = ff[2];

			assert curSentIdConll.equals(curSentIdFrames);
			prevSentId = curSentIdFrames;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	@SuppressWarnings("unused")
	@Override
	public List<FNParse> getParsedSentences() {
		for(FNParse f : this){
			// I just need to run this for its side effect of incrementing fnpl
		}	
		return fnpl;
	}

	private boolean isFullParse(String sentId){
		// isFullParse should be false for lexical examples 
		// from Framenet, and true for the fulltext data
		if(sentId.startsWith("FNFUTXT")) return true;
		if(sentId.startsWith("FNLEX")) return false;
		throw new RuntimeException("Strange unhandled annotationsetid: " + sentId);
	}

	private boolean hasFrameInstanceLabeled(){
		// TODO: What does it mean to have frameInstancesLabeled? 
		// The logic would be placed later on.
		return true;
	}

	@Override
	public Iterator<FNParse> iterator() {
		Iterator<FNParse> it = new Iterator<FNParse>(){
			@Override
			public boolean hasNext() {
				return (litrConll.hasNext() && litrFrames.hasNext());
			}

			@Override
			public FNParse next() {
				FNParse ret;

				List<String> tokens = new ArrayList<String>();
				List<String> pos = new ArrayList<String>();
				List<Integer> gov = new ArrayList<Integer>();
				List<String> depType = new ArrayList<String>();
				List<Integer> tokenCharStart = new ArrayList<Integer>();
				List<Integer> tokenCharEnd = new ArrayList<Integer>();
				while(litrConll.hasNext() && curSentIdConll.equals(prevSentId)){
					String[] l = curLineConllFile.split("\t");
					tokens.add(l[4]);
					pos.add(l[5]);
					gov.add(Integer.parseInt(l[8]));
					depType.add(l[9]);
					tokenCharStart.add(Integer.parseInt(l[6]));
					tokenCharEnd.add(Integer.parseInt(l[7]));
					curLineConllFile=litrConll.nextLine();
					l=curLineConllFile.split("\t");
					curSentIdConll = l[2];
				}				
				Sentence s = new Sentence("FNFUTXT", 
						prevSentId, 
						tokens.toArray(new String[0]), 
						pos.toArray(new String[0]), 
						hasFrameInstanceLabeled(), 
						ArrayUtils.toPrimitive(gov.toArray(new Integer[0])), 
						depType.toArray(new String[0]));
				List<FrameInstance> frameInstances = new ArrayList<FrameInstance>();
				while(litrFrames.hasNext() && curSentIdFrames.equals(prevSentId)){
					Map<String, Span> triggeredRoles = new HashMap<String, Span>();
					// This has a mini FSM inside it.
					// Every time the annotationsetid column changes it makes a new frameinstance
					while(litrFrames.hasNext() && curAnnoSetId.equals(prevAnnoSetId)){
						String[] l=curLineFramesFile.split("\t");
						// Note down all the roles that were triggered.
						String roleName = l[10];
						String nullInstantiation = l[11];
						if(!roleName.equals("NULL") && !roleName.equals("") && nullInstantiation.equals("NULL")){
							triggeredRoles.put(
									roleName, 
									getSpanInTokens(
											Integer.parseInt(l[12]), 
											Integer.parseInt(l[13]),
											tokenCharStart,
											tokenCharEnd
									));
						}
						curLineFramesFile=litrFrames.nextLine();
						curAnnoSetId=(curLineFramesFile.split("\t"))[3];
					}
					String framename = prevFrameName;
					Span targetSpan = getSpanInTokens(
							Integer.parseInt(prevTargetCharStart), 
							Integer.parseInt(prevTargetCharEnd),
							tokenCharStart,
							tokenCharEnd);
					Frame f = frameByName.get(framename);
					Span[] roleSpans = new Span[f.numRoles()];
					int countNonNullSpans = 0;
					for(int i=0; i<roleSpans.length; i++){
						Span tmp = triggeredRoles.get(f.getRole(i));
						if( tmp == null){
							tmp = Span.nullSpan;
						}
						else{
							countNonNullSpans++;
						}
						roleSpans[i]=tmp;
					}
					// Prepare fi, basically fi contains one specific trigger and all its roles.
					FrameInstance fi;
					if(countNonNullSpans > 0){
						fi = FrameInstance.newFrameInstance(f, targetSpan, roleSpans, s);
					}
					else{
						fi = FrameInstance.frameMention(f, targetSpan, s);
					}
					// Add fi to the FrameInstances list before updating the prev[VAR] with cur[VAR]
					frameInstances.add(fi);
					String[] l = curLineFramesFile.split("\t");
					prevAnnoSetId = l[3];
					prevFrameName = l[5];
					prevTargetCharStart = l[7];
					prevTargetCharEnd = l[8];
					curSentIdFrames = l[2];
				}
				ret = new FNParse(s, frameInstances, isFullParse(prevSentId));
				fnpl.add(ret);
				prevSentId = curSentIdFrames;
				return ret; // fn15-fulltext.conll.test fn15-fulltext.frames.test
			}

			private Span getSpanInTokens(int spanCharStart, int spanCharEnd,
					List<Integer> tokenCharStart, List<Integer> tokenCharEnd) {
				int start = Collections.binarySearch(tokenCharStart, spanCharStart);
				start = (start < 0) ? (-start-1) : start;
				int end = Collections.binarySearch(tokenCharEnd, spanCharEnd);
				end = (end < 0) ? -end : (end+1); // We add 1 because the end is supposed to be excluded in Span
				end = (end <= tokenCharEnd.size()) ? end : tokenCharEnd.size();
				return Span.getSpan(start, end);
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
		return it;
	}

}
