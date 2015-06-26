package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.ingest.conll.Conll2011;
import edu.jhu.hlt.concrete.ingest.conll.Ontonotes5;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider.ParseWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ConcreteIO;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Language;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.prim.tuple.Pair;

/**
 * Reads Propbank data (Ontonotes 5) from Concrete form:
 *
 * NOTE: Caching for this class is broken: cannot fit in memory.
 *
 * @author travis
 */
public class PropbankReader {

  public static File CACHE_DIR = new File("/tmp/");

  public static final int LAPTOP = 0;
  public static final int COE_GRID = 1;

  public static File[] ON5_CONLL_PARENT = new File[] {
    new File("/home/travis/code/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data"),
    null,
  };
  public static File[] ON5_RAW = new File[] {
    new File("/home/travis/code/fnparse/data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations"),
    null,
  };

  private File on5;
  private File trainSkels;
  private File devSkels;
  private File testSkels;
  private ConcreteIO cio;
  private MultiAlphabet alph;
  private ParsePropbankData autoParses;

  // Can't cache now: Span is not Serializable (a lot of code uses == instead of .equals())
  public boolean performCaching = false;

  public boolean debug = false;

  // If null, take everything
  private Predicate<Sentence> keep = null;

  /**
   * @param laptop says where to look for data.
   * @param autoParses may be null (won't add them as Stanford parse)
   */
  public PropbankReader(boolean laptop, ParsePropbankData autoParses) {
    alph = new MultiAlphabet();
    cio = new ConcreteIO(null, null, null, Language.EN);
    cio.setConstituencyParseToolname("conll-2011 parse");
    cio.setPropbankToolname("conll-2011 SRL");
    cio.setPosToolName("conll-2011 POS");
    cio.setNerToolName(null);
    int i = laptop ? LAPTOP : COE_GRID;
    on5 = ON5_RAW[i];
    trainSkels = new File(ON5_CONLL_PARENT[i], "train");
    devSkels = new File(ON5_CONLL_PARENT[i], "development");
    testSkels = new File(ON5_CONLL_PARENT[i], "test");
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
    Log.info("starting, " + Describe.memoryUsage());
    Log.info("reading from onotnotes=" + on5.getPath());
    Log.info("reading from skels=" + skelsDir.getPath());

    Conll2011 skels = new Conll2011(f -> f.getName().endsWith(".gold_skel"));
    Ontonotes5 on5 = new Ontonotes5(skels, this.on5);

    Log.info("reading Communications, " + Describe.memoryUsage());
    List<FNParse> parses = new ArrayList<>();
    int docIndex = 0;
    Log.info("converting Communications to Documents/FNParses, " + Describe.memoryUsage());
    for (Communication c : on5.ingest(skelsDir)) {
      Document d = cio.communication2Document(c, docIndex++, alph).getDocument();
      for (FNParse p : DataUtil.convert(d)) {
        Sentence s = p.getSentence();
        if (keep != null && !keep.test(s))
          continue;
        // Add predicted parse information
        if (autoParses != null) {
          if (debug)
            Log.info("adding Sentence.stanfordCParse using the provided parser for " + d.getId());
          s.setStanfordParse(autoParses.parse(s));
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

}
