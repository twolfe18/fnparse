package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.ingest.conll.Conll2011;
import edu.jhu.hlt.concrete.ingest.conll.Ontonotes5;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider.ParseWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ConcreteIO;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.prim.tuple.Pair;

/**
 * Reads Propbank data (Ontonotes 5) from Concrete form:
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
  private File testSkels;
  private ConcreteIO cio;
  private MultiAlphabet alph;

  // Can't cache now: Span is not Serializable (a lot of code uses == instead of .equals())
  public boolean performCaching = false;

  public PropbankReader(boolean useRealTest, boolean laptop) {
    alph = new MultiAlphabet();
    cio = new ConcreteIO(null, null, null);
    cio.setConstituencyParseToolname("conll-2011 parse");
    cio.setPropbankToolname("conll-2011 SRL");
    cio.setPosToolName("conll-2011 POS");
    cio.setNerToolName(null);
    int i = laptop ? LAPTOP : COE_GRID;
    on5 = ON5_RAW[i];

//    trainSkels = new File(ON5_CONLL_PARENT[i], "train");
//    if (useRealTest)
//      testSkels = new File(ON5_CONLL_PARENT[i], "test");
//    else
//      testSkels = new File(ON5_CONLL_PARENT[i], "development");

    // For debugging
    trainSkels = new File(ON5_CONLL_PARENT[i], "development");
    testSkels = new File(ON5_CONLL_PARENT[i], "development");
  }

  public Pair<ItemProvider, ItemProvider> getTrainTestData() {

    // Check the cache
    File cache = getCacheLocation();
    if (performCaching && cache != null && cache.isFile()) {
      ItemProvider[] data = (ItemProvider[]) FileUtil.deserialize(cache);
      assert data.length == 2;
      return new Pair<>(data[0], data[1]);
    }

    // Read the data (slow)
    ItemProvider train = getPropbankItemWrapper(trainSkels);
    ItemProvider test = getPropbankItemWrapper(testSkels);

    // Save back to the cache
    if (performCaching && cache != null)
      FileUtil.serialize(new ItemProvider[] {train, test}, cache);

    return new Pair<>(train, test);
  }

  public File getCacheLocation() {
    int h = trainSkels.getPath().hashCode()
        ^ testSkels.getPath().hashCode();
    return new File(CACHE_DIR, "PRB-CACHE"
        + "_train-" + trainSkels.getName()
        + "_test-" + testSkels.getName()
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
    List<Communication> comms = (List<Communication>) on5.ingest(skelsDir);
    Log.info("converting Communications to Documents/FNParses, " + Describe.memoryUsage());
    // Remove from list to free up memory (yes... you really need to do this, Communications are huge)
    while (comms.size() > 0) {
      Communication c = comms.remove(0);
      Document d = cio.communication2Document(c, docIndex++, alph);
      List<FNParse> cparses = DataUtil.convert(d);
      parses.addAll(cparses);
      if (comms.size() % 500 == 0)
        Log.info("comms.size=" + comms.size() + " " + Describe.memoryUsage());
    }

    boolean lazy = true;
    ParseWrapper pw = new ParseWrapper(parses, lazy);
    Log.info("done, read " + parses.size() + " parses");
    return pw;
  }

}
