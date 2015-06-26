package edu.jhu.hlt.fnparse.data.propbank;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.RedisMap;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.cache.DiskCachedString2TFunc;

/**
 * This class is responsible for parsing the propbank data to create
 * automatically derived trees.
 *
 * Stores just the parses on disk in random/hashed shards.
 *
 * @author travis
 */
public class ParsePropbankData {

  public static class Redis extends ParsePropbankData {
    private RedisMap<ConstituencyParse> rmap;
    private Map<String, ConstituencyParse> extra;
    private Timer pTimer; // parse
    private Timer eTimer; // extra

    public Redis(String host, int port, int db) {
      String prefix = null;
      rmap = new RedisMap<>(prefix, host, port, db,
          SerializationUtils::t2bytes, SerializationUtils::bytes2t);
      extra = new HashMap<>();
      pTimer = new Timer("redis-parse-timer-" + host, 500, false);
      eTimer = new Timer("stanford-parse-timer", 50, true);
    }

    @Override
    public ConstituencyParse parse(Sentence s) {
      pTimer.start();
      String key = s.getId();
      ConstituencyParse cp = rmap.get(key);
      pTimer.stop();
      if (cp == null) {
        eTimer.start();
        cp = extra.get(key);
        if (cp == null) {
          cp = getAnno().getCParse(s);
          extra.put(key, cp);
        }
        eTimer.stop();
      }
      return cp;
    }
  }

  private DiskCachedString2TFunc<ConstituencyParse> cParseF;
  protected ConcreteStanfordWrapper anno;

  private ParsePropbankData() {}

  public ParsePropbankData(File cacheDir, int numShards) {
    if (numShards > 99999)
      throw new IllegalArgumentException();
    this.cParseF = new DiskCachedString2TFunc<>(
        ConstituencyParse::getSentenceId, cacheDir, numShards);
  }

  protected ConcreteStanfordWrapper getAnno() {
    if (anno == null)
      anno = ConcreteStanfordWrapper.getSingleton(false);
    return anno;
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

  public static void parse(boolean laptop, File cacheDir, int numShards) {
    PropbankReader pbr = new PropbankReader(laptop, null);
    ItemProvider ip;

//    File cacheDir = new File("/tmp/parse-data/");
//    int numShards = 300;  // @300 shards, each shard is ~330K
    ParsePropbankData pd = new ParsePropbankData(cacheDir, numShards);

    int parsedSoFar = 0;
    int interval = 1000;

    Log.info("working on train");
    ip = pbr.getTrainData();
    for (int i = 0; i < ip.size(); i++) {
      pd.parse(ip.label(i).getSentence());
      if (parsedSoFar++ % interval == 0)
        Log.info("parsed " + parsedSoFar + " documents so far");
    }

    Log.info("working on dev");
    ip = pbr.getDevData();
    for (int i = 0; i < ip.size(); i++) {
      pd.parse(ip.label(i).getSentence());
      if (parsedSoFar++ % interval == 0)
        Log.info("parsed " + parsedSoFar + " documents so far");
    }

    Log.info("working on test");
    ip = pbr.getTestData();
    for (int i = 0; i < ip.size(); i++) {
      pd.parse(ip.label(i).getSentence());
      if (parsedSoFar++ % interval == 0)
        Log.info("parsed " + parsedSoFar + " documents so far");
    }
  }

  public static void rehash(boolean laptop) {
    DiskCachedString2TFunc<ConstituencyParse> source =
        new DiskCachedString2TFunc<ConstituencyParse>(
            ConstituencyParse::getSentenceId, new File("/tmp/parse-data"), 300);
    int numShards = 2000;
    File target = new File("/tmp/parse-data_" + numShards);
    DiskCachedString2TFunc.rehash(source, target, numShards);
  }

  public static void putIntoRedis(
      File sourceDir, int sourceNumShards,
      String destHost, int destPort, int destDb) {

    // Construct RedisMap
    String prefix = null;
    RedisMap<ConstituencyParse> rmap =
        new RedisMap<>(prefix, destHost, destPort, destDb,
            SerializationUtils::t2bytes, SerializationUtils::bytes2t);

    // This will provide the data
    DiskCachedString2TFunc<ConstituencyParse> source =
        new DiskCachedString2TFunc<ConstituencyParse>(
            ConstituencyParse::getSentenceId, sourceDir, sourceNumShards);

    // Insert into redis
    int interval = 500, adds = 0;
    for (ConstituencyParse cp : source.getAllValues()) {
      rmap.put(cp.getSentenceId(), cp);
      if (adds++ % interval == 0)
        Log.info("added " + adds + " items so far");
    }

    rmap.close();
  }

  public static void main(String[] args) {
//    if (args.length != 3) {
//      System.err.println("please provide:");
//      System.err.println("1) laptop (boolean)");
//      System.err.println("2) cache directory (output)");
//      System.err.println("3) number of shards (integer)");
//    }
//    parse(
//        Boolean.valueOf(args[0]),
//        new File(args[1]),
//        Integer.parseInt(args[2]));

    //rehash(laptop);

    if (args.length != 5) {
      System.err.println("please provide:");
      System.err.println("1) source directory");
      System.err.println("2) source number of shards");
      System.err.println("3) redis server host");
      System.err.println("4) redis server port");
      System.err.println("5) redis server db");
    }
    putIntoRedis(
        new File(args[0]),
        Integer.parseInt(args[1]),
        args[2],
        Integer.parseInt(args[3]),
        Integer.parseInt(args[4]));
  }
}
