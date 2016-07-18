package edu.jhu.hlt.fnparse.data.propbank;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import edu.jhu.hlt.concrete.ingesters.base.IngestException;
import edu.jhu.hlt.concrete.ingesters.conll.Conll2011;
import edu.jhu.hlt.concrete.ingesters.conll.Ontonotes5;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider.ParseWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
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

  /** @deprecated */
  public static File CACHE_DIR = new File("/tmp/");

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
    testSkels = new File(conllParent, "test");  // TODO Switch this to conll-2012-test

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

  public Stream<FNParse> getTrainDataStream() {
    return getParseStreamSafe(trainSkels);
  }
  public Stream<FNParse> getDevDataStream() {
    return getParseStreamSafe(devSkels);
  }
  public Stream<FNParse> getTestDataStream() {
    return getParseStreamSafe(testSkels);
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

  private int docIndex = 0;
  private int getDocIndex() {
    return docIndex++;
  }

  public Stream<FNParse> getParseStreamSafe(File skelsDir) {
    try {
      return getParseStream(skelsDir);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public Stream<FNParse> getParseStream(File skelsDir) throws IOException, IngestException {
    if (debug) {
      Log.info("starting, " + Describe.memoryUsage());
      Log.info("reading from onotnotes=" + on5.getPath());
      Log.info("reading from skels=" + skelsDir.getPath());
    }
    Conll2011 skels = new Conll2011(skelsDir.toPath(), f -> {
      return f.toFile().getName().endsWith(".gold_skel");
    });
    skels.warnOnEmptyCoref = false;
    Ontonotes5 on5 = new Ontonotes5(skels, this.on5.toPath());
    if (debug) {
      skels.debug = true;
      on5.debug = true;
    }

    Log.info("reading Communications, " + Describe.memoryUsage());
    if (debug)
      Log.info("converting Communications to Documents/FNParses, " + Describe.memoryUsage());

    return on5.stream().flatMap(c -> {
        if (debug)
          System.out.println("[getPropbankItemWrapper] c.id=" + c.getId());
        List<FNParse> parses = new ArrayList<>();
        Document d = cio.communication2Document(c, /*docIndex++*/getDocIndex(), alph, Language.EN).getDocument();
        boolean addGoldParse = true;
        boolean addStanfordParse = false;
        boolean addStanfordBasicDParse = false;
        boolean addStanfordCollapsedDParse = false;
        boolean takeGoldPos = true;
        boolean runLemmatizer = true;
        for (FNParse p : DataUtil.convert(d, addGoldParse, addStanfordParse, addStanfordBasicDParse, addStanfordCollapsedDParse, takeGoldPos)) {
          if (debug)
            System.out.println("[parse] p.id=" + p.getId() + " keepIsNull=" + (keep==null));
          Sentence s = p.getSentence();
          if (keep != null && !keep.test(s))
            continue;
          if (runLemmatizer)
            s.lemmatize();
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
        return parses.stream();
    });
  }

  private ItemProvider getPropbankItemWrapper(File skelsDir) {
    try {
      Conll2011 skels = new Conll2011(skelsDir.toPath(), f -> {
        return f.toFile().getName().endsWith(".gold_skel");
      });
      skels.warnOnEmptyCoref = false;
      Ontonotes5 on5 = new Ontonotes5(skels, this.on5.toPath());
      if (debug) {
        skels.debug = true;
        on5.debug = true;
      }

      Log.info("reading Communications, " + Describe.memoryUsage());
      List<FNParse> parses = new ArrayList<>();
//      int docIndex = 0;
      if (debug)
        Log.info("converting Communications to Documents/FNParses, " + Describe.memoryUsage());
      //for (Communication c : on5.ingest()) {  // doesn't work because Stream doesn't implement Iterable...
      on5.stream().forEach(c -> {   // doesn't work because I mutate the docIndex variable...
//      int n = 0;
//      Iterator<Communication> itr = on5.stream().iterator();
//      while (itr.hasNext()) {
//        n++;
//        Communication c = itr.next();
        if (debug)
          System.out.println("[getPropbankItemWrapper] c.id=" + c.getId());
        Document d = cio.communication2Document(c, /*docIndex++*/getDocIndex(), alph, Language.EN).getDocument();
        boolean addGoldParse = true;
        boolean addStanfordParse = false;
        boolean addStanfordBasicDParse = false;
        boolean addStanfordCollapsedDParse = false;
        boolean takeGoldPos = true;
        boolean runLemmatizer = true;
        for (FNParse p : DataUtil.convert(d, addGoldParse, addStanfordParse, addStanfordBasicDParse, addStanfordCollapsedDParse, takeGoldPos)) {
          if (debug)
            System.out.println("[parse] p.id=" + p.getId() + " keepIsNull=" + (keep==null));
          Sentence s = p.getSentence();
          if (keep != null && !keep.test(s))
            continue;
          if (runLemmatizer)
            s.lemmatize();
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
      });
//      if (n == 0)
//        Log.warn("didn't iterate over any Communications?");

      boolean lazy = true;
      ParseWrapper pw = new ParseWrapper(parses, lazy);
      Log.info("done, read " + parses.size() + " parses");
      return pw;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
