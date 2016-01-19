package edu.jhu.hlt.fnparse.data.propbank;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.ingest.conll.Conll2011;
import edu.jhu.hlt.concrete.ingest.conll.Ontonotes5;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider.ParseWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.*;
import edu.jhu.hlt.tutils.ling.Language;
import edu.jhu.prim.tuple.Pair;

/**
 * Reads Propbank data (Ontonotes 5) from Concrete form.
 *
 * NOTE: Caching for this class is broken: cannot fit in memory.
 *
 * @author travis
 */
public class PropbankReader {

  public static File CACHE_DIR = new File("/tmp/");

  public static final int LAPTOP = 0;
  public static final int COE_GRID = 1;

//  public static File[] ON5_CONLL_PARENT = new File[] {
//    new File("/home/travis/code/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data"),
//    new File("/home/hltcoe/twolfe/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data"),
//  };
//  public static File[] ON5_RAW = new File[] {
//    new File("/home/travis/code/fnparse/data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations"),
//    new File("/home/hltcoe/twolfe/fnparse/data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations"),
//  };

  private File on5;
  private File trainSkels;
  private File devSkels;
  private File testSkels;
  private ConcreteToDocument cio;
  private MultiAlphabet alph;
  private ParsePropbankData.Redis autoParses;

  // Can't cache now: Span is not Serializable (a lot of code uses == instead of .equals())
  public boolean performCaching = false;

  // Some features are defined in terms of collapsed or collapsedCC dependency
  // parses. If only basic deps are available, then should we copy those over to
  // the other fields so that those features fire?
  // Will warn if you are over-writing existing depedendency parses.
  public boolean duplicateBasicDeps = true;

  public boolean debug = false;

  // If null, take everything
  private Predicate<Sentence> keep = null;

  /**
   * Uses the java properties data.ontonotes5 and data.propbank.conll
   */
  public PropbankReader(ExperimentProperties config, ParsePropbankData.Redis autoParses) {
    this(config.getExistingDir("data.ontonotes5"),
        config.getExistingDir("data.propbank.conll"),
        autoParses);
  }

  /**
   * @param on5 is the directory containing all of the Ontonotes 5 raw data,
   * e.g. /home/hltcoe/twolfe/fnparse/data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations
   * @param conllParent is the directory containing the conll stand-off files,
   * e.g. /home/hltcoe/twolfe/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data
   * @param autoParses may be null (won't add them as Stanford parse)
   */
  public PropbankReader(File on5, File conllParent, ParsePropbankData.Redis autoParses) {
    alph = new MultiAlphabet();
    cio = new ConcreteToDocument(null, null, null, Language.EN);
    cio.readPropbank();
    cio.log_cons_id_conversion = false;
//    cio.debug_propbank = true;
    cio.keepConcrete = false;

    if (!on5.isDirectory())
      throw new IllegalArgumentException("not a valid ON5 directory: " + on5.getPath());
    this.on5 = on5;

    if (!conllParent.isDirectory())
      throw new IllegalArgumentException("not a valid conll directory: " + on5.getPath());
    trainSkels = new File(conllParent, "train");
    devSkels = new File(conllParent, "development");
    testSkels = new File(conllParent, "test");

    this.autoParses = autoParses;
  }

  public void setKeep(Predicate<Sentence> keep) {
    this.keep = keep;
  }

  public ItemProvider getTrainData() {
    return getPropbankItemWrapper(trainSkels);
  }

  public ItemProvider getDevData() {
    return getPropbankItemWrapper(devSkels);
  }

  public ItemProvider getTestData() {
    return getPropbankItemWrapper(testSkels);
  }

  public Pair<ItemProvider, ItemProvider> getTrainTestData() {
    return getData(trainSkels, testSkels);
  }

  public Pair<ItemProvider, ItemProvider> getTrainDevData() {
    return getData(trainSkels, devSkels);
  }

  public Pair<ItemProvider, ItemProvider> getData(File f1, File f2) {

    // Check the cache
    File cache = getCacheLocation(f1, f2);
    if (performCaching && cache != null && cache.isFile()) {
      ItemProvider[] data = (ItemProvider[]) FileUtil.deserialize(cache);
      assert data.length == 2;
      return new Pair<>(data[0], data[1]);
    }

    // Read the data (slow)
    ItemProvider train = getPropbankItemWrapper(f1);
    ItemProvider test = getPropbankItemWrapper(f2);

    // Save back to the cache
    if (performCaching && cache != null)
      FileUtil.serialize(new ItemProvider[] {train, test}, cache);

    return new Pair<>(train, test);
  }

  public static File getCacheLocation(File f1, File f2) {
    int h = f1.getPath().hashCode()
        ^ f2.getPath().hashCode();
    return new File(CACHE_DIR, "PRB-CACHE"
        + "_f1-" + f1.getName()
        + "_f2-" + f2.getName()
        + "_tag-" + h
        + ".jser.gz");
  }

  private ItemProvider getPropbankItemWrapper(File skelsDir) {
    if (debug) {
      Log.info("starting, " + Describe.memoryUsage());
      Log.info("reading from onotnotes=" + on5.getPath());
      Log.info("reading from skels=" + skelsDir.getPath());
    }

    Conll2011 skels = new Conll2011(f -> f.getName().endsWith(".gold_skel"));
    skels.warnOnEmptyCoref = false;
    Ontonotes5 on5 = new Ontonotes5(skels, this.on5);

    Log.info("reading Communications, " + Describe.memoryUsage());
    List<FNParse> parses = new ArrayList<>();
    int docIndex = 0;
    if (debug)
      Log.info("converting Communications to Documents/FNParses, " + Describe.memoryUsage());
    for (Communication c : on5.ingest(skelsDir)) {
      if (debug)
        System.out.println("[getPropbankItemWrapper] c.id=" + c.getId());
      Document d = cio.communication2Document(c, docIndex++, alph, Language.EN).getDocument();
      for (FNParse p : DataUtil.convert(d)) {
        if (debug)
          System.out.println("[parse] p.id=" + p.getId() + " keepIsNull=" + (keep==null));
        Sentence s = p.getSentence();
        if (keep != null && !keep.test(s))
          continue;
        // Add predicted parse information
        if (autoParses != null) {
          if (debug)
            Log.info("adding Sentence.stanfordCParse using the provided parser for " + d.getId());
          if (s.getStanfordParse(false) == null)
            s.setStanfordParse(autoParses.parse(s));
          if (s.getBasicDeps(false) == null)
            s.setBasicDeps(autoParses.getBasicDeps(s));
        }
        if (duplicateBasicDeps) {
          if (s.getCollapsedDeps(false) != null)
            Log.warn("overwriting collapsed depdencies!");
          s.setCollapsedDeps(s.getBasicDeps(false));
        }
        parses.add(p);
      }
      if (docIndex % 500 == 0)
        Log.info("converted " + docIndex + " docs so far, " + Describe.memoryUsage());
    }

    boolean lazy = true;
    ParseWrapper pw = new ParseWrapper(parses, lazy);
    Log.info("done, read " + parses.size() + " parses");
    return pw;
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);

    DataUtil.DEBUG = config.getBoolean("debug.DataUtil", false);
    boolean showParses = config.getBoolean("showParses", true);

    ParsePropbankData.Redis propbankAutoParses = null;
    PropbankReader pbr = new PropbankReader(config, propbankAutoParses);

    ItemProvider tr = pbr.getTrainData();
    int n = tr.size();
    Log.info("train.size=" + n);
    for (int i = 0; i < n; i++) {
      FNParse y = tr.label(i);
      if (showParses)
        System.out.println(Describe.fnParse(y));
    }
    Log.info("done");
  }
}
