package edu.jhu.hlt.fnparse.data.propbank;

import java.io.File;

import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.cache.DiskCachedString2TFunc;

/**
 * This class is responsible for parsing the propbank data to create
 * automatically derived trees.
 *
 * @author travis
 */
public class ParseData {

  private DiskCachedString2TFunc<ConstituencyParse> cParseF;
  private ConcreteStanfordWrapper anno;

  public ParseData(File cacheDir, int numShards) {
    if (numShards >= 100000)
      throw new IllegalArgumentException();
    this.anno = ConcreteStanfordWrapper.getSingleton(false);
    this.cParseF = new DiskCachedString2TFunc<>(
        ConstituencyParse::getSentenceId, cacheDir, numShards);
  }

  /**
   * Tries to get the parse from disk cache first, then parses and saves if not
   * found. Does not store (mutate) the parse in the sentence.
   */
  public ConstituencyParse parse(Sentence s) {
    ConstituencyParse cp = this.cParseF.get(s.getId(), () -> anno.getCParse(s));
    cp.dropBase();
    return cp;
  }

  public static void main(String[] args) {
    boolean laptop = true;
    PropbankReader pbr = new PropbankReader(laptop);
    ItemProvider ip;

    File cacheDir = new File("/tmp/parse-data/");
    int numShards = 300;
    ParseData pd = new ParseData(cacheDir, numShards);

    Log.info("working on train");
    ip = pbr.getTrainData();
    for (int i = 0; i < ip.size(); i++)
      pd.parse(ip.label(i).getSentence());

    Log.info("working on dev");
    ip = pbr.getDevData();
    for (int i = 0; i < ip.size(); i++)
      pd.parse(ip.label(i).getSentence());

    Log.info("working on test");
    ip = pbr.getTestData();
    for (int i = 0; i < ip.size(); i++)
      pd.parse(ip.label(i).getSentence());
  }
}
