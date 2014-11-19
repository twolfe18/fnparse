package edu.jhu.hlt.fnparse.inference.pruning;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.RedisFileCache;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public class ArgPruner implements Serializable, IArgPruner {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(ArgPruner.class);

  private static ArgPruner singleton;

  public static ArgPruner getInstance() {
    if (singleton == null) {
      singleton = new ArgPruner(
          TargetPruningData.getInstance(),
          SemaforicHeadFinder.getInstance());
    }
    return singleton;
  }

  /**
   * just creates an arg pruner and memoizes the results on disk
   * 
   * TODO update for roleSynsetMap
   */
  public static void main(String[] args) throws IOException {
    boolean plain = true;	// just write out the data

    File parent = new File("toydata/arg-pruning");
    ParserParams params = new ParserParams();
    HasFeatureAlphabet featureNames = null;
    FrameIdStage fid = null;  //new FrameIdStage(params, featureNames);
    ArgPruner ap = null;  //new ArgPruner(
        //fid.params.getTargetPruningData(), params.headFinder);

    if(plain) {
      ap.clearCachedFiles();
      ap.serialize();
      return;
    }

    MultiTimer t = new MultiTimer();

    t.start("data");
    Set<LexicalUnit>[][] map = ap.getRoleWordMap();
    t.stop("data");

    t.start("byHand");
    writeLUTensor(new File(parent, "byHand.gz"), ap.getRoleWordMap());
    t.stop("byHand");

    t.start("oosGz");
    ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(new File(parent, "oos.gz"))));
    oos.writeObject(map);
    oos.close();
    t.stop("oosGz");

    t.start("oosSer");
    oos = new ObjectOutputStream(new FileOutputStream(new File(parent, "oos.ser")));
    oos.writeObject(map);
    oos.close();
    t.stop("oosSer");

    /*
     * hand-rolled solution is 2x the speed and 1/2 the size
     * for about 40 lines of trivial code. 
     *
     * data:   <Timer data   1.50 sec and 1 calls total, 1.5 sec/call>
     * byHand: <Timer byHand 0.40 sec and 1 calls total, 0.4 sec/call>
     * oosGz:  <Timer oosGz  0.97 sec and 1 calls total, 1.0 sec/call>
     * oosSer: <Timer oosSer 1.36 sec and 1 calls total, 1.4 sec/call>
     *
     * du -sch toydata/arg-pruning/*
     * 744K	toydata/arg-pruning/byHand.gz
     * 1.4M	toydata/arg-pruning/oos.gz
     * 4.2M	toydata/arg-pruning/oos.ser
     */
    LOG.info(t);
  }

  public static final Set<String> pennPunctuationPosTags =
      new HashSet<String>(Arrays.asList(":", ".", "--", ",", "(", ")", "$", "``", "\"", "''"));
  public static final Set<String> pennOtherPrunePosTags =
      new HashSet<String>(Arrays.asList("#", "$", "FW", "EX", "POS", "LS"));

  public static enum LexPruneMethod {
    NONE,
    SYNSET,
    EXACT
  }

  //private ParserParams params;
  private TargetPruningData targetPruningData;
  private HeadFinder headFinder;
  private boolean pruneByPOS;
  private LexPruneMethod lexMethod;

  private int argsKept = 0;
  private int argsPrunedPos = 0;
  private int argsPrunedLex = 0;
  private int argsFalsePruned = 0;
  private int argsPruningInterval = 125000;	// <=0 for no reporting

  @Override
  public double pruneRatio() {
    int pruned = argsPrunedLex + argsPrunedPos;
    int total = pruned + argsKept;
    if(total == 0) return 0d;
    return ((double) pruned) / total;
  }

  @Override
  public void falsePrune() {
    argsFalsePruned++;
  }

  @Override
  public int numFalsePrunes() {
    return argsFalsePruned;
  }

  // indexing is [frameId][roleId], null Sets mean this role was never seen in data
  private transient Set<LexicalUnit>[][] roleWordMap;		// words that have appeared as the headword of [frame.id][roleIdx]
  private transient Set<LexicalUnit>[][] roleSynsetMap;	// an expansion of roleWordMap by closure over WN synsets
  private File persistRoleWordMapTo = new File("toydata/arg-pruning/roleWordMap.gz");
  private File persistRoleSynsetMapTo = new File("toydata/arg-pruning/roleSynsetMap.gz");
  private Pattern year = Pattern.compile("[12]\\d\\d\\d" + "|[12]\\d\\d0'?s" + "|'?[1-9]0'?s");

  public ArgPruner(TargetPruningData targetPruningData, HeadFinder headFinder) {
    lexMethod = LexPruneMethod.SYNSET;
    pruneByPOS = true;
    this.targetPruningData = targetPruningData;
    this.headFinder = headFinder;

    if(targetPruningData == null)
      throw new IllegalArgumentException();
    if(headFinder == null)
      throw new IllegalArgumentException();
  }

  public void set(boolean pos, LexPruneMethod lexMethod) {
    this.pruneByPOS = pos;
    this.lexMethod = lexMethod;
  }

  public synchronized void clearCachedFiles() {
    if(persistRoleSynsetMapTo != null)
      persistRoleSynsetMapTo.delete();
    if(persistRoleWordMapTo != null)
      persistRoleWordMapTo.delete();
  }

  public synchronized void serialize() {
    writeLUTensor(persistRoleSynsetMapTo, getRoleSynsetMap());
    writeLUTensor(persistRoleWordMapTo, getRoleWordMap());
  }

  // this is the method we care about!
  @Override
  public boolean pruneArgHead(Frame f, int roleIdx, int headWordIdx, Sentence sentence) {
    String word = sentence.getWord(headWordIdx);
    if (year.matcher(word).matches())
      return false;
    String pos = sentence.getPos(headWordIdx);
    if (pruneByPOS && (pos.endsWith("DT") || pennPunctuationPosTags.contains(pos) || pennOtherPrunePosTags.contains(pos))) {
      argsPrunedPos++;
      return true;
    }
    if (lexMethod != LexPruneMethod.NONE) {
      Set<LexicalUnit> possibleLUs;
      if (lexMethod == LexPruneMethod.EXACT) {
        possibleLUs = getRoleWordMap()[f.getId()][roleIdx];
      } else if (lexMethod == LexPruneMethod.SYNSET) {
        possibleLUs = getRoleSynsetMap()[f.getId()][roleIdx];
      } else {
        assert false : "need to update this code for: " + lexMethod;
        possibleLUs = null;
      }
      argsPrunedLex++;
      if(possibleLUs == null)
        return true;
      LexicalUnit lu = sentence.getFNStyleLUUnsafe(
          headWordIdx, targetPruningData.getWordnetDict(), true);
      if (lu == null || possibleLUs.contains(lu))
        return true;
      argsPrunedLex--;
    }
    argsKept++;

    if (argsPruningInterval > 0 && argsKept % argsPruningInterval == 0) {
      LOG.info(String.format("[ArgPruner] pruned %.1f %% of args seen, %d "
          + "by POS, %d by lex, with %d false prunes",
          pruneRatio()*100d,
          argsPrunedPos,
          argsPrunedLex,
          argsFalsePruned));
    }

    return false;
  }

  @SuppressWarnings("unchecked")
  private synchronized void init() {
    if (roleSynsetMap != null && roleWordMap != null)
      return;
    if (persistRoleWordMapTo != null && persistRoleWordMapTo.isFile() &&
        persistRoleSynsetMapTo != null && persistRoleSynsetMapTo.isFile()) {
      roleWordMap = readLUTensor(persistRoleWordMapTo);
      roleSynsetMap = readLUTensor(persistRoleSynsetMapTo);
    } else {
      // compute the word lists
      Timer t = Timer.start("[ArgPruner]");
      LOG.info("[ArgPruner] building role word and synset maps...");
      roleWordMap = new Set[FrameIndex.framesInFrameNet+1][];
      roleSynsetMap = new Set[FrameIndex.framesInFrameNet+1][];
      FrameIndex fi = FrameIndex.getInstance();
      IDictionary dict = targetPruningData.getWordnetDict();
      WordnetStemmer stemmer = new WordnetStemmer(dict);
      for (Frame f : fi.allFrames()) {
        int K = f.numRoles();
        Set<LexicalUnit>[] roleWords = new Set[K];
        Set<LexicalUnit>[] roleSynset = new Set[K];
        for (FrameInstance frameInst : targetPruningData.getPrototypesByFrame(f)) {
          for (int k = 0; k < K; k++) {
            Span s = frameInst.getArgument(k);
            if (s == Span.nullSpan) continue;

            int argHead = s.start;
            if (s.width() > 1)
              argHead = headFinder.head(s, frameInst.getSentence());
            LexicalUnit lu;
            try {
              lu = frameInst.getSentence().getFNStyleLUUnsafe(
                  argHead, dict, true);
            } catch(Exception e) {
              e.printStackTrace();
              continue;
            }

            // don't want to recover for cases where we can't do the proper
            // FN POS tag conversion or if we can't figure out the lemma
            if(lu == null)
              continue;

            // ======= EXACT WORD MATCH ==================
            Set<LexicalUnit> ws = roleWords[k];
            if (ws == null) {
              ws = new HashSet<LexicalUnit>();
              roleWords[k] = ws;
            }
            ws.add(lu);

            // ======= SYNSET MATCH ======================
            ws = roleSynset[k];
            if (ws == null) {
              ws = new HashSet<LexicalUnit>();
              roleSynset[k] = ws;
            }
            POS pos = PosUtil.fn2wordNet(lu.pos);
            if(pos == null) continue;
            List<String> stems = stemmer.findStems(lu.word, pos);
            if(stems == null) continue;
            for (String st : stems) {
              IIndexWord iw = dict.getIndexWord(st, pos);
              if (iw == null) continue;
              for (IWordID wid : iw.getWordIDs()) {
                IWord w = dict.getWord(wid);
                for (IWord syn : w.getSynset().getWords()) {
                  LexicalUnit synLU = new LexicalUnit(syn.getLemma(), syn.getPOS().toString());
                  ws.add(synLU);
                }
              }
            }
          }
        }
        roleWordMap[f.getId()] = roleWords;
        roleSynsetMap[f.getId()] = roleSynset;
      }
      t.stop();

      // save this data for later
      if (persistRoleWordMapTo != null)
        writeLUTensor(persistRoleWordMapTo, roleWordMap);
      if (persistRoleSynsetMapTo != null)
        writeLUTensor(persistRoleSynsetMapTo, roleSynsetMap);
    }
  }

  public Set<LexicalUnit>[][] getRoleWordMap() {
    if (roleWordMap == null)
      init();
    return roleWordMap;
  }

  public Set<LexicalUnit>[][] getRoleSynsetMap() {
    if (roleSynsetMap == null)
      init();
    return roleSynsetMap;
  }

  private static void writeLUTensor(File f, Set<LexicalUnit>[][] saa) {
    LOG.info("[ArgPruner writeLUTensor] writing to " + f.getPath());
    try {
      // write out (frame.id, role.id, numWords, word*)
      DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)));
      for(int i=0; i<saa.length; i++) {
        for(int j=0; j<saa[i].length; j++) {
          Set<LexicalUnit> s = saa[i][j];
          if(s == null) continue;
          dos.writeInt(i);
          dos.writeInt(j);
          dos.writeInt(s.size());
          for(LexicalUnit lu : s)
            LexicalUnit.writeTo(lu, dos);
        }
      }
      // i write a -1 at the end to signify that there are no more frames
      dos.writeInt(-1);
      dos.close();
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  private static final RedisFileCache RFC = null;
      //new RedisFileCache("nov3", "localhost", 6379, 0);

  @SuppressWarnings("unchecked")
  public static Set<LexicalUnit>[][] readLUTensor(File f) {
    LOG.info("[ArgPruner readLUTensor] reading from " + f.getPath());
    try {
      Set<LexicalUnit>[][] saa = new Set[FrameIndex.framesInFrameNet+1][];
      FrameIndex fi = FrameIndex.getInstance();
      for(Frame fr : fi.allFrames())
        saa[fr.getId()] = new Set[fr.numRoles()];

      DataInputStream dis;
      if (RFC != null && RFC.hasInpuStreamFor(f.getPath())) {
        LOG.info("[readLUTensor] using redis file cache: " + RFC);
        dis = new DataInputStream(new GZIPInputStream(RFC.getInpuStreamFor(f.getPath())));
      } else {
        LOG.info("[readLUTensor] using disk");
        dis = new DataInputStream(new GZIPInputStream(new FileInputStream(f)));
      }
      while (true) {
        int frameId = dis.readInt();
        if(frameId == -1) break;
        int roleId = dis.readInt();
        int numLUs = dis.readInt();
        Set<LexicalUnit> lus = new HashSet<LexicalUnit>();
        for(int i=0; i<numLUs; i++)
          lus.add(LexicalUnit.readFrom(dis));
        saa[frameId][roleId] = lus;
      }
      dis.close();
      return saa;
    } catch(Exception e) { throw new RuntimeException(e); }
  }
}
