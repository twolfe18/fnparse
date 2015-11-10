package edu.jhu.hlt.fnparse.data.propbank;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.RedisMap;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.cache.DiskCachedString2TFunc;

/**
 * This class is responsible for parsing the Propbank data to create
 * automatically derived trees.
 *
 * Stores just the parses on disk in random/hashed shards.
 *
 * @author travis
 */
public class ParsePropbankData {

  public static class Redis extends ParsePropbankData {
    public static final String NONE = "none";
    private RedisMap<ConstituencyParse> rmapCons;
    private RedisMap<DependencyParse> rmapBasicDeps;
    private Map<String, ConstituencyParse> extra;
    private Timer pTimer, eTimer; // cparse
    private Timer deTimer, dpTimer;
    public boolean logGets = false;
    public boolean logParses = true;

    // If you attempt to find a key and its not there, should you try to put the
    // computed result back into the RedisMap?
    public boolean insertComputedValues = true;

    // using ExperimentProperties: same keys no matter where you use it (nice)
    public Redis(ExperimentProperties config) {
      this(config.getString("redis.host.propbankParses", NONE),
          config.getInt("redis.port.propbankParses", -1),
          config.getInt("redis.db.propbankParses", -1));
    }

    public Redis(String host, int port, int db) {
      String prefix = "conl2011/";
      if (host != NONE) {
        rmapCons = new RedisMap<>(prefix, host, port, db,
            SerializationUtils::t2bytes, SerializationUtils::bytes2t);
        rmapBasicDeps = new RedisMap<>(prefix, host, port, db,
            SerializationUtils::t2bytes, SerializationUtils::bytes2t);
      } else {
        Log.info("no redis host given, will fail if used");
      }
      extra = new HashMap<>();
      pTimer = new Timer("redis-cparse-timer-" + host, 500, false);
      eTimer = new Timer("stanford-cparse-timer", 50, true);
      dpTimer = new Timer("redis-dparse-timer-" + host, 500, false);
      deTimer = new Timer("stanford-dparse-timer", 50, true);
    }

    static String getBasicDepsKey(Sentence s) {
      return s.getId() + "/basicDeps";
    }
    static String getStanfordParseKey(Sentence s) {
      return s.getId();
    }

    public DependencyParse getBasicDeps(Sentence s) {
      assert s.getBasicDeps(false) == null;
      String key = getBasicDepsKey(s);
      if (logGets)
        System.out.println("[ParsePropbankData.Redis] fetching dparse for " + key);
      DependencyParse dp = null;
      if (rmapBasicDeps != null) {
        dpTimer.start();
        dp = rmapBasicDeps.get(key);
        dpTimer.stop();
      }
      if (dp == null) {
        if (logParses)
          System.out.println("[ParsePropbankData.Redis] no dparse for " + key + ", parsing");
        deTimer.start();
        dp = getAnno().getBasicDParse(s);
        if (insertComputedValues && rmapBasicDeps != null)
          rmapBasicDeps.put(key, dp);
        deTimer.stop();
      }
      return dp;
    }

    @Override
    public ConstituencyParse parse(Sentence s) {
      String key = getStanfordParseKey(s);
      if (logGets)
        System.out.println("[ParsePropbankData.Redis] fetching cparse for " + key);
      ConstituencyParse cp = null;
      if (rmapCons != null) {
        pTimer.start();
        cp = rmapCons.get(key);
        pTimer.stop();
      }
      if (cp == null) {
        if (logParses)
          System.out.println("[ParsePropbankData.Redis] no cparse for " + key + ", parsing");
        eTimer.start();
        cp = extra.get(key);
        if (cp == null) {
          cp = getAnno().getCParse(s);
          if (insertComputedValues) {
            extra.put(key, cp);
            if (rmapCons != null)
              rmapCons.put(key, cp);
          }
        }
        eTimer.stop();
      }
      return cp;
    }
  }


  /*
   * NOTE: Everything below here is DEPRECATED. Disk is a terrible solution for this.
   */


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

  public static void parse(File cacheDir, int numShards) {
    PropbankReader pbr = new PropbankReader(ExperimentProperties.getInstance(), null);
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

  /**
   * Reads the redis config from ExperimentProperties, see Redis constructor. Also requires the
   * "shards" and "numShards" properties.
   */
  public static void putPropbankDParsesIntoRedis(ExperimentProperties config) {

    int shard = config.getInt("shard");
    int nShard = config.getInt("numShards");

    // null so that it doesn't parse
    PropbankReader pbr = new PropbankReader(config, null);

    // This is where we will put the parses (redis front-end)
    ParsePropbankData.Redis redisParses = new ParsePropbankData.Redis(config);

    int interval = 10;
    int parsed = 0;
    int alreadyParsed = 0;
    int total = 0;
    for (ItemProvider ip : Arrays.asList(pbr.getDevData(), pbr.getTestData(), pbr.getTestData())) {
      Iterator<FNParse> iter = ip.iterator();
      while (iter.hasNext()) {
        total++;
        FNParse y = iter.next();
        Sentence s = y.getSentence();
        String key = Redis.getBasicDepsKey(s);
        // Only take one shard
        if (Math.floorMod(key.hashCode(), nShard) == shard) {
          DependencyParse dp = redisParses.rmapBasicDeps.get(key);
          if (dp == null) {
            parsed++;
            dp = redisParses.getBasicDeps(s);
            redisParses.rmapBasicDeps.put(key, dp);
          } else {
            alreadyParsed++;
          }

          if (parsed % interval == 0)
            System.out.println("parsed=" + parsed + " alreadyParsed=" + alreadyParsed + " total=" + total);
        }
      }
    }
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

    ExperimentProperties config = ExperimentProperties.init(args);
    putPropbankDParsesIntoRedis(config);

//    if (args.length != 5) {
//      System.err.println("please provide:");
//      System.err.println("1) source directory");
//      System.err.println("2) source number of shards");
//      System.err.println("3) redis server host");
//      System.err.println("4) redis server port");
//      System.err.println("5) redis server db");
//    }
//    putIntoRedis(
//        new File(args[0]),
//        Integer.parseInt(args[1]),
//        args[2],
//        Integer.parseInt(args[3]),
//        Integer.parseInt(args[4]));
  }
}
