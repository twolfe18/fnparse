package edu.jhu.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.ingesters.conll.CoNLLX;
import edu.jhu.hlt.concrete.ingesters.conll.ConcreteToCoNLLX;
import edu.jhu.hlt.concrete.merge.MergeTokenAlignedCommunications;
import edu.jhu.hlt.ikbp.tac.IndexCommunications;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.tuple.Pair;

/**
 * Parse with parsey (syntaxnet) offline and batch (i.e. efficiently).
 * You give an instance a working directory and the first time you ask
 * for parses, it will return either an existing parse or a dummy one,
 * and record the need to parse that document. The next time you instantiate
 * this class with the same working directory, it will parse those docs
 * and load them into memory to return the next time you ask for a parse.
 *
 * For now this only supports commId.comm and commId.conll files,
 * so don't use this with an extravagant number of files (~10^5)
 * on a crappy file system (my laptop SSD doesn't count...).
 *
 * NOTE: There is nothing which requires this class to call parsey,
 * it generalizes to any dependency parser which takes/makes CoNLL-X
 * and can be pointed to with a script.
 *
 * @author travis
 */
public class OfflineBatchParseyAnnotator {
  public static final String PARSEY_TOOLNAME = "parsey";
  public static final String SUF_META = ".section-info.aux";
  public static final String SUF_CONLL = ".conll";
  
  public static final File PARSEY_SCRIPT_LAPTOP = new File("/home/travis/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh");
  
  // where memo is stored on disk
  private File cacheDir;

  // where memo is stored in memory
  private Map<String, Pair<byte[], byte[]>> commId2conll;   // values are (conll, sectionMeta)

  // location of parsey
  private File parseyScript;

  // misc
  public boolean verbose = false;
  public Counts<String> ec = new Counts<>();  // event counts for debugging


  public OfflineBatchParseyAnnotator(File cacheDir, File parseyConllScript) throws IOException {
    this(cacheDir, parseyConllScript, true, true);
  }

  /**
   * @param cacheDir is a working directory for storing parsed and unparsed text.
   * @param parseyScript is a script which takes conllx input and produces conllx output.
   * Make sure your PATH is configured properly so that you can run this script without error.
   * @param parse says whether the parser should be run immediately in the constructor
   * @param load says whether parsed text should be loaded into memory in the constructor
   */
  public OfflineBatchParseyAnnotator(File cacheDir, File parseyConllScript, boolean parse, boolean load) throws IOException {
    if (cacheDir.isFile())
      throw new IllegalArgumentException();
    if (!cacheDir.isDirectory())
      cacheDir.mkdirs();
    this.cacheDir = cacheDir;
    this.commId2conll = new HashMap<>();
    this.parseyScript = parseyConllScript;
    if (parse)
      parse();
    if (load)
      loadCache();
  }
  
  /** returns a directory containing .conll (CoNLL-X format) which haven't been processed yet */
  private File getQueuedConllxDir() {
    File f = new File(cacheDir, "unlabeled");
    if (!f.isDirectory())
      f.mkdirs();
    return f;
  }
  
  /** returns a directory containing .conll (CoNLL-X format) files which have been labeled */
  private File getProcessedConllxDir() {
    File f = new File(cacheDir, "labeled");
    if (!f.isDirectory())
      f.mkdirs();
    return f;
  }
  

  /**
   * Load parsed text in the working directory into memory (by default this
   * happens at construction, no need to call twice).
   */
  public void loadCache() {
    File p = getProcessedConllxDir();
    File[] cc = p.listFiles();
    if (cc == null) return;
    for (File f : cc) {
      if (f.isFile() && f.getName().endsWith(SUF_CONLL)) {
        int c = f.getName().lastIndexOf('.');
        String commId = f.getName().substring(0, c);
        File meta = new File(p, commId + SUF_META);
        assert meta.isFile();
        try {
          byte[] conll = Files.readAllBytes(f.toPath());
          byte[] metabytes = Files.readAllBytes(meta.toPath());
          Object old = commId2conll.put(commId, new Pair<>(conll, metabytes));
          assert old == null;
          ec.increment("loadedComm");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
  
  /**
   * Calls batch offline parsing via parsey script (by default this happens at
   * construction, no need to call twice unless there are new comms to parse).
   */
  public void parse() throws IOException {
    if (verbose)
      Log.info("parsing");

    ec.increment("offlineParse");
    File in = getQueuedConllxDir();
    File[] inConll = in.listFiles(f -> f.getName().endsWith(SUF_CONLL));
    
    if (inConll == null || inConll.length == 0) {
      if (verbose)
        Log.info("nothing to parse in " + in.getPath());
      return;
    }

    // Create a shell script which calls parsey offline!
    File runParsey = new File(cacheDir, "run-parsey.sh");
    Log.info("making " + runParsey.getPath());
    File annos = new File(cacheDir, "parseyAnnos" + SUF_CONLL);
    try (BufferedWriter w = FileUtil.getWriter(runParsey)) {
      w.write("find " + getQueuedConllxDir().getPath() + " -name '*" + SUF_CONLL + "' | xargs cat | " + parseyScript.getPath() + " >" + annos.getPath());
      w.newLine();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    
    // Run the script
    ProcessBuilder pb = new ProcessBuilder("/bin/bash", runParsey.getPath());
    pb.environment().put("PATH", System.getenv("PATH"));
    Process p = pb.start();
    InputStreamGobbler stdout = new InputStreamGobbler(p.getInputStream());
    InputStreamGobbler stderr = new InputStreamGobbler(p.getErrorStream());
    stdout.start();
    stderr.start();
    
    int ret = 1;
    try {
      ret = p.waitFor();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    
    if (verbose) {
      System.out.println("ret:    " + ret);
      System.out.println("stdout: " + stdout.getLines());
      System.out.println("stderr: " + stderr.getLines());
    }
    

    // Split the annotations in parseyAnnos.conll into separate files in labeled/
    File out = getProcessedConllxDir();
    try (BufferedReader r = FileUtil.getReader(annos)) {
      for (File ic : inConll) {
        File meta = new File(in, ic.getName().replaceAll(SUF_CONLL + "$", SUF_META));
        File outConll = new File(out, ic.getName());
        File outMeta = new File(out, meta.getName());

        System.out.println(meta.getPath() + "  =>  " + outConll.getPath());
        ec.increment("offlineParse/conllFile");
        try (BufferedWriter w = FileUtil.getWriter(outConll)) {

          // There is one line per sentence
          // <kind> <tab> <label> <tab> <numberList>
          List<String> metaLines = Files.readAllLines(meta.toPath());
          int n = metaLines.size();
          for (int i = 0; i < n; i++) {
            // Read a sentence from the conll file
            for (String cl = r.readLine(); cl != null; cl = r.readLine()) {
              w.write(cl);
              w.newLine();
              if (cl.isEmpty())
                break;
            }
          }
        }
        
        // Remove the unlabeled
        ic.delete();
        
        // Copy over the meta
        FileUtil.copy(meta, outMeta);
        meta.delete();
      }
    }
    
    // Remove the all-in-one conll annotations
    annos.delete();
  }
  
  public Communication annotate(Communication c) {
    if (verbose)
      Log.info("starting comm=" + c.getId());
    ec.increment("annotate");
    Pair<byte[], byte[]> buf = commId2conll.get(c.getId());
    if (buf != null) {
      if (verbose)
        Log.info("returning from memory");
      ec.increment("annotate/fromMemo");
      return annotateFromMemory(c, buf.get1(), buf.get2());
    }
    
    // Record the need to parse this comm
    ConcreteToCoNLLX c2conll = new ConcreteToCoNLLX();
//    c2conll.verbose = true;
    File p = getQueuedConllxDir();
    try {
      c2conll.dump(c, new File(p, c.getId() + SUF_CONLL), new File(p, c.getId() + SUF_META));
    } catch (IOException e) {
      e.printStackTrace();
    }

    // Make a dummy parse called "parsey"
    Communication copy = new Communication(c);
    for (Tokenization t : new TokenizationIter(copy)) {
      ec.increment("fakeParse");
      DependencyParse faker;
      if (t.isSetDependencyParseList() && t.getDependencyParseListSize() > 0) {
        // Copy the first parse to stand in temporarily as parsey
        faker = new DependencyParse(t.getDependencyParseList().get(0));
        faker.getMetadata().setTool(PARSEY_TOOLNAME);
        ec.increment("fakeParse/copyFirstParse");
      } else {
        // Make a fake tree where each node is a child of root
        faker = new DependencyParse();
        faker.setMetadata(new AnnotationMetadata()
            .setTimestamp(System.currentTimeMillis() / 1000)
            .setTool(PARSEY_TOOLNAME));
        int n = t.getTokenList().getTokenListSize();
        for (int i = 0; i < n; i++) {
          faker.addToDependencyList(new Dependency()
              .setDep(i)
              .setEdgeType("root"));
        }
        ec.increment("fakeParse/depthOneTree");
      }
      t.addToDependencyParseList(faker);
    }
    return copy;
  }
  
  public static Communication annotateFromMemory(Communication c, byte[] conll, byte[] meta) {
    
    // Convert meta to List<String>
    List<String> metaLines = new ArrayList<>();
    try (ByteArrayLineIter iter = new ByteArrayLineIter(meta)) {
      while (iter.hasNext())
        metaLines.add(iter.next());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    try (ByteArrayLineIter iter = new ByteArrayLineIter(conll)) {
      boolean showTiming = false;
      SimpleImmutableEntry<CoNLLX, Communication> p = CoNLLX.readCommunication(c.getId(), iter, PARSEY_TOOLNAME, showTiming);

      // One section
      Communication onlyParsey = p.getValue();
      
      // Restore section structure
      CoNLLX cx = new CoNLLX(PARSEY_TOOLNAME);
      cx.setCommunicationId(onlyParsey);
      cx.groupBySections(onlyParsey, metaLines);

      MergeTokenAlignedCommunications mta = new MergeTokenAlignedCommunications(c, onlyParsey);
      return mta.getUnion();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * View a byte[] as if it were an Iterator<String> of lines in a file.
   *
   * @author travis
   */
  public static class ByteArrayLineIter implements AutoCloseable, Iterator<String> {
    private BufferedReader r;
    private String cur;
    
    public ByteArrayLineIter(byte[] bytes) {
      if (bytes == null)
        throw new IllegalArgumentException();
      ByteArrayInputStream is = new ByteArrayInputStream(bytes);
      InputStreamReader isr = new InputStreamReader(is);
      r = new BufferedReader(isr);
      advance();
    }
    
    private void advance() {
      try {
        cur = r.readLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean hasNext() {
      return cur != null;
    }

    @Override
    public String next() {
      String t = cur;
      advance();
      return t;
    }

    @Override
    public void close() throws Exception {
      r.close();
    }
  }
  
  
  private static Communication getOneComm() throws Exception {
    File f = new File("data/concretely-annotated-gigaword/sample-with-semafor-nov08/nyt_eng_200909.tar.gz");
    try (AutoCloseableIterator<Communication> iter = new IndexCommunications.FileBasedCommIter(Arrays.asList(f))) {
      return iter.next();
    }
  }
  
  public static void test() throws Exception {
    Communication c = getOneComm();
    OfflineBatchParseyAnnotator p = new OfflineBatchParseyAnnotator(new File("/tmp/offline-parsey"), PARSEY_SCRIPT_LAPTOP);
    Communication c2 = p.annotate(c);
    for (Tokenization t : new TokenizationIter(c2)) {
      int D = t.getDependencyParseListSize();
      DependencyParse d = t.getDependencyParseList().get(D-1);
      System.out.println(D + "\t" + d);
    }
//    File f = new File("/tmp/offline-parsey/labeled/NYT_ENG_20090921.0040.conll");
//    byte[] b = Files.readAllBytes(f.toPath());
//    System.out.println("bytes.length=" + b.length);
//    try (ByteArrayLineIter iter = new ByteArrayLineIter(b)) {
//      int i = 0;
//      while (iter.hasNext()) {
//        System.out.println((i++) + "\t" + iter.next());
//      }
//    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("please provide:");
      System.err.println("1) a offline-parsey working directory");
      System.err.println("2) path to a parsey conll script");
      System.exit(1);
    }
//    System.out.println("path: " + System.getenv("PATH"));
//    test();
    File wd = new File(args[0]);
    File script = new File(args[1]);
    OfflineBatchParseyAnnotator p = new OfflineBatchParseyAnnotator(wd, script, true, false);
    System.out.println("event counts: " + p.ec);
    Log.info("done");
  }

}
