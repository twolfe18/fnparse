package edu.jhu.hlt.fnparse.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.tutils.Timer;

/**
 * TODO This class needs to drop the assumption that the entire cache can fit
 * in memory. See {@link ParsePropbankData} for a memory-free disk caching implementation.
 *
 * @author travis
 */
public class CachingConcreteStanfordWrapper extends ConcreteStanfordWrapper {
  private File bdParseCacheFile;
  private File cParseCacheFile;
  private Map<String, DependencyParse> bdParseCache;
  private Map<String, ConstituencyParse> cParseCache;
  private boolean addToCache;

  public CachingConcreteStanfordWrapper(File bdParseCache, File cParseCache, boolean addToCache) {
    this.bdParseCache = new HashMap<>();
    this.cParseCache = new HashMap<>();
    this.bdParseCacheFile = bdParseCache;
    this.cParseCacheFile = cParseCache;
    this.addToCache = addToCache;
  }

//  @Override
//  public DependencyParse getBasicDParse(Sentence s) {
//    DependencyParse deps = null;
//    if (bdParseCache != null && (deps = bdParseCache.get(s.getId())) != null)
//      return deps;
//    deps = super.getBasicDParse(s);
//    if (this.addToCache)
//      bdParseCache.put(s.getId(), deps);
//    return deps;
//  }

//  @Override
//  public ConstituencyParse getCParse(Sentence s) {
//    ConstituencyParse cons = null;
//    if (cParseCache != null && (cons = cParseCache.get(s.getId())) != null)
//      return cons;
//    cons = super.getCParse(s);
//    if (this.addToCache)
//      cParseCache.put(s.getId(), cons);
//    return cons;
//  }

  public void load() {
    if (bdParseCacheFile.isFile()) {
      LOG.info("[load] addToCache=" + addToCache + " loading from " + bdParseCacheFile.getPath());
      CacheSerUtil.load(bdParseCacheFile, bdParseCache, DependencyParse.DESERIALIZATION_FUNC);
    } else {
      LOG.info("no bdParseCache file: " + bdParseCacheFile.getPath());
    }
    if (cParseCacheFile.isFile()) {
      LOG.info("[load] addToCache=" + addToCache + " loading from " + cParseCacheFile.getPath());
      CacheSerUtil.load(cParseCacheFile, cParseCache, ConstituencyParse.DESERIALIZATION_FUNC);
    } else {
      LOG.info("no cParseCache file: " + cParseCacheFile.getPath());
    }
    LOG.info("done loading from caches");
  }

  public void saveCache() {
    LOG.info("saving to " + bdParseCache.size() + " items to " + bdParseCacheFile.getPath());
    CacheSerUtil.save(
        bdParseCacheFile, bdParseCache, DependencyParse.SERIALIZATION_FUNC);
    LOG.info("saving to " + cParseCache.size() + " items to " + cParseCacheFile.getPath());
    CacheSerUtil.save(
        cParseCacheFile, cParseCache, ConstituencyParse.SERIALIZATION_FUNC);
    LOG.info("done saving to caches");
  }

  public void absorb(CachingConcreteStanfordWrapper other) {
    LOG.info("absorbing " + other.bdParseCache.size() + " items into bdParseCache");
    this.bdParseCache.putAll(other.bdParseCache);
    LOG.info("absorbing " + other.cParseCache.size() + " items into cParseCache");
    this.cParseCache.putAll(other.cParseCache);
  }

  public static void buildCacheAndSaveToDisk(
      int part, int numParts, CachingConcreteStanfordWrapper parser) {
    if (part >= numParts || part < 0)
      throw new IllegalArgumentException();
    int n = 0;
    Timer t = new Timer();
    t.setPrintInterval(50);
    for (FileFrameInstanceProvider fip : Arrays.asList(
        FileFrameInstanceProvider.dipanjantrainFIP,
        FileFrameInstanceProvider.dipanjantestFIP,
        FileFrameInstanceProvider.fn15lexFIP)) {
      Iterator<FNParse> iter = fip.getParsedSentences();
      while (iter.hasNext()) {
        FNParse p = iter.next();
        int h = p.hashCode();
        if (h < 0) h = -h;
        if (h % numParts != part)
          continue;
        n++;
        t.start();
//        parser.getBasicDParse(p.getSentence());
//        parser.getCParse(p.getSentence());
        t.stop();
        throw new RuntimeException("reimplement me");
      }
    }
    LOG.info("parsed " + n + " sentences, about to save");
    parser.saveCache();
    LOG.info("done");
  }

  public static void main(String[] args) {
    if (args.length != 4) {
      System.out.println("please provide:");
      System.out.println("1) A directory to store cache pieces in");
      System.out.println("2) either \"map\" or \"reduce\" if you want to compute the first or second stage of the job"); 
      System.out.println("3) a piece that is relevant");
      System.out.println("4) number of pieces");
      return;
    }
    File workingDir = new File(args[0]);
    String mode = args[1];
    int piece = Integer.parseInt(args[2]);
    int numPieces = Integer.parseInt(args[3]);
    if (!workingDir.isDirectory())
      throw new IllegalArgumentException("not a directory: " + workingDir.getPath());

    List<File> bdc = new ArrayList<>();
    List<File> cc = new ArrayList<>();
    for (int i = 0; i < numPieces; i++) {
      bdc.add(new File(workingDir, String.format("bdParseCache-%05d.bin", i)));
      cc.add(new File(workingDir, String.format("cParseCache-%05d.bin", i)));
    }

    if ("map".equals(mode)) {
      CachingConcreteStanfordWrapper p =
          new CachingConcreteStanfordWrapper(bdc.get(piece), cc.get(piece), true);
      buildCacheAndSaveToDisk(piece, numPieces, p);
    } else if ("reduce".equals(mode)) {
      File bdParseCache = new File(workingDir, "bdParseCache-all.bin");
      File cParseCache = new File(workingDir, "cParseCache-all.bin");
      CachingConcreteStanfordWrapper all =
          new CachingConcreteStanfordWrapper(bdParseCache, cParseCache, true);
      for (int i = 0; i < numPieces; i++) {
        if (!bdc.get(i).isFile() || !cc.get(i).isFile()) {
          LOG.warn("can't find files for piece " + i);
          continue;
        }
        LOG.info("absorbing " + i);
        CachingConcreteStanfordWrapper a =
            new CachingConcreteStanfordWrapper(bdc.get(i), cc.get(i), true);
        a.load();
        all.absorb(a);
      }
      LOG.info("saving");
      all.saveCache();
    } else {
      throw new IllegalArgumentException("illegal mode: " + mode);
    }
  }
}
