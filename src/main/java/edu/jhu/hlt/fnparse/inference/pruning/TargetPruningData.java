package edu.jhu.hlt.fnparse.inference.pruning;

import java.io.*;
import java.util.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.experiment.SpanPruningExperiment;
import edu.mit.jwi.*;
import edu.mit.jwi.data.ILoadPolicy;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

// TODO make an init() method that observes a single pass over lex examples
// so that we only ever have to make one pass (rather than one per data-structure)
public class TargetPruningData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	public static final boolean debug = false;

	private TargetPruningData() {}	// singleton
	private static final TargetPruningData singleton = new TargetPruningData();
	public static TargetPruningData getInstance() { return singleton; }
	
	
	private void init() {
		long start = System.currentTimeMillis();
		System.out.println("[TargetPruningData] init starting...");
		IRAMDictionary dict = getWordnetDict();
		WordnetStemmer stemmer = new WordnetStemmer(dict);
		prototypesByStem = new HashMap<String, List<FrameInstance>>();
		prototypesByFrame = new HashMap<Frame, List<FrameInstance>>();
		Iterator<FNTagging> iter = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
		while(iter.hasNext()) {
			FNTagging p = iter.next();
			Sentence s = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				
				List<FrameInstance> prototypes = prototypesByFrame.get(fi.getFrame());
				if(prototypes == null) {
					prototypes = new ArrayList<FrameInstance>();
					prototypes.add(fi);
					prototypesByFrame.put(fi.getFrame(), prototypes);
				}
				else prototypes.add(fi);
				
				Span target = fi.getTarget();
				if(target.width() != 1) continue;
				assert target.width() == 1;
				String word = s.getWord(target.start);
				
				// TODO talk to pushpendre about why this is happening
				if(word.length() == 0) {
					System.err.println("length 0 word in: " + s.toString());
					continue;
				}
				
				POS pos = PosUtil.ptb2wordNet(s.getPos(target.start));
				if(debug) {
					System.out.printf("[TargetPruningData init] frame=%s word=%s pos=%s\n",
							fi.getFrame().getName(), word, pos);
				}
				for(String stem : stemmer.findStems(word, pos)) {
					if(debug) {
						System.out.printf("[TargetPruningData init] frame=%s word=%s pos=%s stem=%s\n",
								fi.getFrame().getName(), word, pos, stem);
					}
					List<FrameInstance> lfi = prototypesByStem.get(stem);
					if(lfi == null) {
						lfi = new ArrayList<FrameInstance>();
						lfi.add(fi);
						prototypesByStem.put(stem, lfi);
					}
					else lfi.add(fi);
				}
			}
		}
		System.out.printf("[TargetPruningData] done in %.1f seconds.\n", (System.currentTimeMillis()-start)/1000d);
	}
	
	
	private transient IRAMDictionary dict;
	public IRAMDictionary getWordnetDict() {
		if(dict == null) {
			long start = System.currentTimeMillis();
			File f = new File("toydata/wordnet/dict");
			dict = new RAMDictionary(f, ILoadPolicy.IMMEDIATE_LOAD);
			try { dict.open(); }
			catch(Exception e) {
				throw new RuntimeException(e);
			}
			long time = System.currentTimeMillis() - start;
			System.out.printf("loaded wordnet in %.1f seconds\n", time/1000d);
		}
		return dict;
	}

	private transient Map<String, List<FrameInstance>> prototypesByStem;
	public Map<String, List<FrameInstance>> getPrototypesByStem() {
		if(prototypesByStem == null)
			init();
		return prototypesByStem;
	}
	
	
	// what i want is the same Frame -> List[Prototype] mapping that i would
	// have built for prototype vars anyway
	private transient Map<Frame, List<FrameInstance>> prototypesByFrame;
	public Map<Frame, List<FrameInstance>> getPrototypesByFrame() {
		if(prototypesByFrame == null)
			init();
		return prototypesByFrame;
	}
	
	private transient Map<LexicalUnit, List<Frame>> lu2frames;
	/**
	 * keys use Penn treebank POS tags (not Framenet POS tags)
	 */
	public Map<LexicalUnit, List<Frame>> getLU2Frames() {
		if(lu2frames == null) {
			System.out.println("[TriggerPruningParams] building LU => List<Frame> index...");
			lu2frames = new HashMap<LexicalUnit, List<Frame>>();
			int numF = 0;
			for(Frame f : FrameIndex.getInstance().allFrames()) {
				for(int i=0; i<f.numLexicalUnits(); i++) {
					// the FN => Penn tagset mapping is lossy
					// but... i could just add all Penn tags that
					// agree with the FN tag to this hashmap.
					LexicalUnit lu = f.getLexicalUnit(i);
					List<String> possiblePennTags = PosUtil.getFrameNetPosToAllPennTags().get(lu.pos);
					for(String pos : possiblePennTags) {
						LexicalUnit newLU = new LexicalUnit(lu.word, pos);
						List<Frame> lf = lu2frames.get(newLU);
						if(lf == null) {
							lf = new ArrayList<Frame>();
							lf.add(f);
							lu2frames.put(newLU, lf);
						}
						else lf.add(f);
						numF += 1;
					}
				}
			}
			System.out.printf("[TargetPruningData] lu2frames contains %d keys and %.1f Frames/key\n",
					lu2frames.size(), ((double)numF) / lu2frames.size());
		}
		return lu2frames;
	}
	/**
	 * keys use Penn treebank POS tags (not Framenet POS tags)
	 */
	public List<Frame> getFramesFromLU(LexicalUnit lu) {
		List<Frame> fs = getLU2Frames().get(lu);
		if(fs == null) fs = Collections.emptyList();
		return fs;
	}
	
	
	private transient Set<String> stopwordsForTargets;
	public boolean isTargetStopword(String word) {
		if(stopwordsForTargets == null) {
			stopwordsForTargets = new HashSet<String>();
			stopwordsForTargets.addAll(SpanPruningExperiment.lthSpecialWords);
			stopwordsForTargets.addAll(SpanPruningExperiment.alsoOfIterest);
			stopwordsForTargets.remove("in");	// about 3% of width1 targets in train data
			assert stopwordsForTargets.size() > 5;
		}
		return stopwordsForTargets.contains(word);
	}
	
	public boolean prune(int index, Sentence s) {
		LexicalUnit lu = s.getLU(index);
		if(isTargetStopword(lu.word)) return true;
		if(lu.pos.endsWith("DT")) return true;	// DT and PDT, 0.4% of width1 targets in train data
		return false;
	}

}
