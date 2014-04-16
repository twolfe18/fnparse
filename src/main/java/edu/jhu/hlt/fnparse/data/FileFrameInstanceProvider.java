package edu.jhu.hlt.fnparse.data;

import java.io.*;
import java.util.*;

import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.ArrayUtils;

import edu.jhu.hlt.fnparse.datatypes.*;

public class FileFrameInstanceProvider implements FrameInstanceProvider {
	
	public static final class FIIterator implements Iterator<FNTagging> {
		
		private LineIterator litrFrames;
		private LineIterator litrConll;

		private String curLineFramesFile;
		private String curSentIdFrames;
		
		// The following 2 only occur in frames file so need to add Frames suffix
		private String curAnnoSetId;
		private String prevAnnoSetId;

		private String prevFrameName;
		private String prevTargetCharStart;
		private String prevTargetCharEnd;

		private String curLineConllFile;
		private String curSentIdConll;	

		private String prevSentIdConll;
		private String prevSentIdFrames;
		
		
		private Map<String, Frame> frameByName;
		
		public FIIterator(File frameFile, File conllFile) {
			
			// I need to print who is calling this iterator function so I can figure out why I'm using so much memory
			System.err.println("[FIIterator] iterating over " +
					frameFile.getPath() + " and " + conllFile.getPath());
//			StackTraceElement[] stack = Thread.currentThread().getStackTrace();
//			int n = Math.min(stack.length, 15);
//			for(int i=0; i<n; i++)
//				System.err.println("\t" + stack[i]);
			
			try {
				litrFrames = new LineIterator(new FileReader(frameFile));
				litrConll = new LineIterator(new FileReader(conllFile));
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

				//assert curSentIdConll.equals(curSentIdFrames);
				prevSentIdFrames = curSentIdFrames;
				prevSentIdConll = curSentIdConll;
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public boolean hasNext() {
			return (litrConll.hasNext() && litrFrames.hasNext());
		}

		@Override
		public FNTagging next() {
			List<String> tokens = new ArrayList<String>();
			List<String> pos = new ArrayList<String>();
			List<String> lemmas = new ArrayList<String>();
			List<Integer> gov = new ArrayList<Integer>();
			List<String> depType = new ArrayList<String>();
			List<Integer> tokenCharStart = new ArrayList<Integer>();
			List<Integer> tokenCharEnd = new ArrayList<Integer>();
			String lastConllLine = null;
			while(litrConll.hasNext() && curSentIdConll.equals(prevSentIdConll)){
				String[] l = curLineConllFile.split("\t");
				tokens.add(l[4]);
				pos.add(l[10]);
				if(l.length == 11) {
					lemmas.add(l[4]);
				}else{
					lemmas.add(l[11]);
				}
				gov.add(Integer.parseInt(l[8]));
				depType.add(l[9]);
				tokenCharStart.add(Integer.parseInt(l[6]));
				tokenCharEnd.add(Integer.parseInt(l[7]));
				lastConllLine = curLineConllFile;
				curLineConllFile=litrConll.nextLine();
				l=curLineConllFile.split("\t");
				curSentIdConll = l[2];
			}
			assert tokens.size() > 0 : "last conll line: " + lastConllLine;
			String datasetOfSentence;
			if(curSentIdConll.startsWith("FNFUTXT"))
				datasetOfSentence = "FNFUTXT";
			else if(curSentIdConll.startsWith("FNLEX"))
				datasetOfSentence = "FNLEX";
			else if(curSentIdConll.startsWith("SL"))
				datasetOfSentence = "SemLink";
			else throw new RuntimeException("where did " + curSentIdConll + " come from?");

			Sentence s = new Sentence( datasetOfSentence, 
					prevSentIdConll, 
					tokens.toArray(new String[0]), 
					pos.toArray(new String[0]),
					lemmas.toArray(new String[0]),
					ArrayUtils.toPrimitive(gov.toArray(new Integer[0])), 
					depType.toArray(new String[0]));
			
			List<FrameInstance> frameInstancesForFNTagging = new ArrayList<FrameInstance>();
			if(prevSentIdConll.equals(curSentIdFrames)){
				while(litrFrames.hasNext() && curSentIdFrames.equals(prevSentIdFrames)){
					Map<String, Span> triggeredRoles = new HashMap<String, Span>();
					// This has a mini FSM inside it.
					// Every time the annotationsetid column changes it makes a new frameinstance
					while(litrFrames.hasNext() && curAnnoSetId.equals(prevAnnoSetId)){
						String[] l=curLineFramesFile.split("\t");
						// Note down all the roles that were triggered.
						String roleName = l[10];
						String nullInstantiation = l[11]; // the itype column
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
					if(f==null){
						// There is a frame in the fulltext data called Test35, which is clearly erroeneous.
						// Earlier I just manually filtered it out.
						// But semafor was not so for the sake of comparability I am dealing with it in code.
						// It really doesn't make a lot of sense, but for the sake of comparability.
						assert framename.equals("Test35");
					} else {
						Span[] roleSpans = new Span[f.numRoles()];
						Arrays.fill(roleSpans, Span.nullSpan);
						for(int i=0; i<roleSpans.length; i++){
							Span tmp = triggeredRoles.get(f.getRole(i));
							if(tmp != null) roleSpans[i] = tmp;					
						}
						FrameInstance fi = FrameInstance.newFrameInstance(f, targetSpan, roleSpans, s);
						frameInstancesForFNTagging.add(fi);
					}
					// Add fi to the FrameInstances list before updating the prev[VAR] with cur[VAR]
					// but add fi only if 
					String[] l = curLineFramesFile.split("\t");
					prevAnnoSetId = l[3];
					prevFrameName = l[5];
					prevTargetCharStart = l[7];
					prevTargetCharEnd = l[8];
					curSentIdFrames = l[2];
				}
			}

			FNTagging ret = isFullParse(s.getId())
				? new FNParse(s, frameInstancesForFNTagging)
				: new FNTagging(s, frameInstancesForFNTagging);
			prevSentIdFrames = curSentIdFrames;
			prevSentIdConll = curSentIdConll;
			return ret;
		}
		
		private boolean isFullParse(String sentId){
			// isFullParse should be false for lexical examples 
			// from Framenet, and true for the fulltext data
			if(sentId.startsWith("FNFUTXT")) return true;
			if(sentId.startsWith("FNLEX")) return false;
			if(sentId.startsWith("SL")) return true;
			throw new RuntimeException("unhandled annotationsetid: " + sentId);
		}

		private Span getSpanInTokens(int spanCharStart, int spanCharEnd,
				List<Integer> tokenCharStart, List<Integer> tokenCharEnd) {
			int start = Collections.binarySearch(tokenCharStart, spanCharStart);
			start = (start < 0) ? (-start-1) : start;
			int end = Collections.binarySearch(tokenCharEnd, spanCharEnd);
			end = (end < 0) ? -end : (end+1); // We add 1 because the end is supposed to be excluded in Span
			end = (end <= tokenCharEnd.size()) ? end : tokenCharEnd.size();
			if(start==end && start == 0) end++;
			if(start==end && start != 0) start--;
			return Span.getSpan(start, end);
		}

		@Override
		public void remove() { throw new UnsupportedOperationException(); }
	}

	public static final FileFrameInstanceProvider fn15trainFIP =
			new FileFrameInstanceProvider(UsefulConstants.TrainFN15FullTextFramesPath, UsefulConstants.TrainFN15FullTextConllPath);
	
	public static final FileFrameInstanceProvider fn15testFIP =
			new FileFrameInstanceProvider(UsefulConstants.TestFN15FullTextFramesPath, UsefulConstants.TestFN15FullTextConllPath);
	
	public static final FileFrameInstanceProvider fn15lexFIP =
			new FileFrameInstanceProvider(UsefulConstants.FN15LexicographicFramesPath, UsefulConstants.FN15LexicographicConllPath);
	
	public static final FileFrameInstanceProvider dipanjantrainFIP =
		new FileFrameInstanceProvider(UsefulConstants.TrainDipanjanFramesPath, UsefulConstants.TrainDipanjanConllPath);

	public static final FileFrameInstanceProvider dipanjantestFIP =
		new FileFrameInstanceProvider(UsefulConstants.TestDipanjanFramesPath, UsefulConstants.TestDipanjanConllPath);

//	public static final FileFrameInstanceProvider semlinkFIP =
//			new FileFrameInstanceProvider(UsefulConstants.SemLinkFramesPath, UsefulConstants.SemLinkConllPath);

	private File frameFile, conllFile;
	
	@Override
	public String getName() { return toString(); }
	
	@Override
	public String toString() {
		return String.format("<FrameInstanceProvider from %s %s>",
				this.frameFile.getName(), this.conllFile.getName());
	}
	
	public FileFrameInstanceProvider(File frameFile, File conllFile) {
		this.frameFile = frameFile;
		this.conllFile = conllFile;
	}

	@Override
	public Iterator<FNParse> getParsedSentences() {
		return new FNIterFilters.OnlyParses(getParsedOrTaggedSentences());
	}

	@Override
	public Iterator<FNTagging> getTaggedSentences() {
		return new FNIterFilters.OnlyTaggings(getParsedOrTaggedSentences());
	}

	@Override
	public Iterator<FNTagging> getParsedOrTaggedSentences() {
		return new FNIterFilters.SkipExceptions(new FIIterator(frameFile, conllFile));
	}
}
