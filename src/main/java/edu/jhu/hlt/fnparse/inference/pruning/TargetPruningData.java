package edu.jhu.hlt.fnparse.inference.pruning;

import java.io.*;
import java.util.*;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.experiment.SpanPruningExperiment;
import edu.jhu.hlt.tutils.WordNetPosUtil;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.RAMDictionary;
import edu.mit.jwi.data.ILoadPolicy;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public class TargetPruningData implements Serializable {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(TargetPruningData.class);
  public static final boolean DEBUG = false;

  private TargetPruningData() {}	// singleton
  private static final TargetPruningData singleton = new TargetPruningData();
  public static TargetPruningData getInstance() { return singleton; }

  private transient IRAMDictionary dict;
  public synchronized IRAMDictionary getWordnetDict() {
    if (dict == null) {
      long start = System.currentTimeMillis();
      File f = new File("toydata/wordnet/dict");
      dict = new RAMDictionary(f, ILoadPolicy.IMMEDIATE_LOAD);
      try { dict.open(); }
      catch(Exception e) {
        throw new RuntimeException(e);
      }
      long time = System.currentTimeMillis() - start;
      LOG.info(String.format(
          "loaded wordnet in %.1f seconds", time/1000d));
    }
    return dict;
  }

	private transient WordnetStemmer stemmer;
	public WordnetStemmer getStemmer() {
		if(stemmer == null)
			stemmer = new WordnetStemmer(getWordnetDict());
		return stemmer;
	}

  private static int initCalls = 0;
  /**
   * Only use this if you want to guarantee when the data is loaded
   * (it calls the automatically otherwise)
   */
  private synchronized void init() {
    long start = System.currentTimeMillis();
    LOG.info("init starting... " + (++initCalls));
    if (initCalls > 1)
      throw new RuntimeException();
    IRAMDictionary dict = getWordnetDict();
    WordnetStemmer stemmer = new WordnetStemmer(dict);
    assert prototypesByStem == null;
    assert prototypesByFrame == null;
    prototypesByStem = new HashMap<>();
    prototypesByFrame = new HashMap<>();
    Iterator<FNTagging> iter = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
    while (iter.hasNext()) {
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
        if(target.width() != 1)
          continue;
        String word = s.getWord(target.start);
        POS pos = WordNetPosUtil.ptb2wordNet(s.getPos(target.start));

        // TODO talk to pushpendre about why this is happening
        if(word.length() == 0) {
          LOG.warn("length 0 word in: " + s.toString());
          continue;
        }

        if(DEBUG) {
          System.out.printf("[init] frame=%s word=%s pos=%s\n",
              fi.getFrame().getName(), word, pos);
        }
        List<String> stems = stemmer.findStems(word, pos);
        if (stems.size() == 0) {
          //System.err.printf("[TargetPruningData init] can't stem %s with pos %s\n",
          //		word, s.getPos(target.start));
          stems = Arrays.asList(word + "-UNSTEMMED");
        }
        for(String stem : stems) {
          if(DEBUG) {
            System.out.printf("[init] frame=%s word=%s pos=%s stem=%s\n",
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
    LOG.info(String.format("[TargetPruningData] done in %.1f seconds.",
        (System.currentTimeMillis()-start)/1000d));
  }

  public List<FrameInstance> getPrototypesByStem(
      int headIdx, Sentence s, boolean lowercase) {
    if (prototypesByStem == null)
      init();
    IRAMDictionary wnDict = getWordnetDict();
    WordnetStemmer stemmer = new WordnetStemmer(wnDict);
    String word = s.getWord(headIdx);
    if (lowercase)
      word = word.toLowerCase();
    POS pos = WordNetPosUtil.ptb2wordNet(s.getPos(headIdx));
    List<String> stems = null;
    try { stems = stemmer.findStems(word, pos); }
    catch(IllegalArgumentException e) {
      // Words that normalized to an empty string throw an exception
      return Collections.<FrameInstance>emptyList();
    }
    if (stems.size() == 0) {
      //System.err.printf("[getPrototypesByStem] problem stemming: %s with "
      //		+ "pos %s\n", word, s.getPos(headIdx));
      stems = Arrays.asList(word + "-UNSTEMMED");
    }
    List<FrameInstance> fis = new ArrayList<>();
    for(String stem : stems) {
      List<FrameInstance> protos = prototypesByStem.get(stem);
      if(protos == null) continue;
      fis.addAll(protos);
    }
    return fis;
  }

  private transient Map<String, List<FrameInstance>> prototypesByStem;
  public synchronized Map<String, List<FrameInstance>> getPrototypesByStem() {
    if(prototypesByStem == null)
      init();
    return prototypesByStem;
  }

  // What i want is the same Frame -> List[Prototype] mapping that I would
  // have built for prototype vars anyway
  private transient Map<Frame, List<FrameInstance>> prototypesByFrame;
  public synchronized Map<Frame, List<FrameInstance>> getPrototypesByFrame() {
    if(prototypesByFrame == null)
      init();
    return prototypesByFrame;
  }
  public List<FrameInstance> getPrototypesByFrame(Frame f) {
    Map<Frame, List<FrameInstance>> prototypes = getPrototypesByFrame();
    List<FrameInstance> ret = prototypes.get(f);
    if(ret == null) ret = Collections.emptyList();
    return ret;
  }

  private transient Map<String, Map<String, List<Frame>>> word2pos2frames;
  /**
   * Gets frames that have this word listed as a lexical unit in the frame
   * index.
   * @param word is case-sensitive
   *             (e.g. "Thursday" is in there, "thursday" is not)
   * @return
   */
  public List<Frame> getLUFramesByWord(String word) {
    if (word2pos2frames == null)
      initLexicalUnitData();
    Map<String, List<Frame>> fsmap = word2pos2frames.get(word);
    if (fsmap == null) return Collections.<Frame>emptyList();
    List<Frame> fs = new ArrayList<>();
    for (List<Frame> fl : fsmap.values())
      fs.addAll(fl);
    return fs;
  }

  /**
   * keys are LU's from FrameNet (note that POS tag set is not PTB)
   */
  private synchronized void initLexicalUnitData() {
    LOG.info("[TargetPruningData] building LU => List<Frame> index...");
    word2pos2frames = new HashMap<String, Map<String, List<Frame>>>();
    for(Frame f : FrameIndex.getInstance().allFrames()) {
      int nLU = f.numLexicalUnits();
      for(int i=0; i<nLU; i++) {
        LexicalUnit lu = f.getLexicalUnit(i);

        // "protection_((entity)).n" -> "protection.n"
        int j = lu.word.indexOf("_(");
        if(j > 0)
          lu = new LexicalUnit(lu.word.substring(0, j), lu.pos);

        Map<String, List<Frame>> pos2frames = word2pos2frames.get(lu.word);
        if (pos2frames == null) {
          pos2frames = new HashMap<>();
          word2pos2frames.put(lu.word, pos2frames);
        }
        List<Frame> frames = pos2frames.get(lu.pos);
        if (frames == null) {
          frames = new ArrayList<>();
          pos2frames.put(lu.pos, frames);
        }
        frames.add(f);
      }
    }
  }

  /**
   * keys are LU's from FrameNet (note that POS tag set is not PTB)
   */
  public List<Frame> getFramesFromLU(LexicalUnit lu) {
    if (word2pos2frames == null)
      initLexicalUnitData();
    Map<String, List<Frame>> fmap = word2pos2frames.get(lu.word);
    if (fmap == null) return Collections.emptyList();
    List<Frame> fs = fmap.get(lu.pos);
    if (fs == null) return Collections.emptyList();
    return fs;
  }

  private transient Set<String> stopwordsForTargets;
  public synchronized boolean isTargetStopword(String word) {
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
    if (isTargetStopword(lu.word)) return true;
    if (lu.pos.endsWith("DT")) return true;	// DT and PDT, 0.4% of width1 targets in train data
    if (".".equals(lu.pos)
        || ",".equals(lu.pos)
        || ":".equals(lu.pos)
        || "--".equals(lu.pos)
        || "``".equals(lu.pos)
        || "\"".equals(lu.pos)
        || "(".equals(lu.pos)
        || ")".equals(lu.pos))
      return true;
    return false;
  }
}
