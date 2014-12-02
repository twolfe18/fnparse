package edu.jhu.hlt.fnparse.evaluation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;

/**
 * A wrapper around Semafor's wrapper around SemEval'07 evaluation script.
 *
 * @author travis
 */
public class SemaforEval {
  public static final Logger LOG = Logger.getLogger(SemaforEval.class);
  public static File SEMEVAL07_SCRIPT =
      new File("/home/travis/code/semafor/scripts/scoring/fnSemScore_modified.pl");
  public static File SEMAFOR_PREPARE_ANNO_SCRIPT =
      new File("/home/travis/code/semafor/prepare_xml_anno.sh");
  public static File FRAMES_SINGLE_FILE =
      new File("/home/travis/Desktop/code/fnparse/from-CMU/framenet15/"
          + "framesSingleFile.xml");
  public static File RELATION_MODIFIED_FILE =
      new File("/home/travis/Desktop/code/fnparse/from-CMU/framenet15/"
          + "frRelationModified.xml");

  private File workingDir;

  public SemaforEval(File workingDir) {
    if (!workingDir.isDirectory())
      throw new IllegalArgumentException();
    this.workingDir = workingDir;
  }

  public static void main(String[] args) {
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    SemaforEval eval = new SemaforEval(new File("/tmp"));
    LOG.info(eval.evaluate(parses, parses));
  }

  public static <T> void writeByLine(List<T> items, Function<T, String> show, File f) {
    LOG.info("[writeByLine] writing " + items.size() + " items " + f.getPath());
    try (FileWriter w = new FileWriter(f)) {
      for (T t : items) {
        w.write(show.apply(t));
        w.write("\n");
      }
    } catch (Exception e) {
      throw new RuntimeException();
    }
  }

  public static void generateAllLemmaTagsFile(List<Sentence> sentences, File f) {
    Function<Sentence, String> show = s -> {
      StringBuilder sb = new StringBuilder();
      String sep = "\t";
      int n = s.size();
      assert n > 0;
      sb.append(String.valueOf(n));
      for (int i = 0; i < n; i++) {   // Word
        sb.append(sep);
        sb.append(s.getWord(i));
        assert s.getWord(i).length() > 0;
      }
      for (int i = 0; i < n; i++) {   // POS
        sb.append(sep);
        sb.append(s.getPos(i));
      }
      ConcreteStanfordWrapper parser = ConcreteStanfordWrapper.getSingleton(false);
      parser.parse(s, true);
      DependencyParse deps = s.getBasicDeps(false);
      assert deps != null;
      for (int i = 0; i < n; i++) {   // dep label
        sb.append(sep);
        sb.append(deps.getLabel(i));
      }
      List<Integer> roots = new ArrayList<>();
      for (int i = 0; i < n; i++) {   // dep parent (1-indexed)
        sb.append(sep);
        sb.append(String.valueOf(deps.getHead(i) + 1));
        if (deps.isRoot(i))
          assert deps.getHead(i) == -1;
        if (deps.getHead(i) == -1)
          roots.add(i);
      }
      if (roots.size() == 0) {
        assert false;
      }
      for (int i = 0; i < n; i++) {   // NER
        sb.append(sep);
        sb.append("O");
      }
      for (int i = 0; i < n; i++) {   // lemma
        sb.append(sep);
        sb.append(s.getLemma(i));
      }
      return sb.toString();
    };
    writeByLine(sentences, show, f);
  }

  public static void generateTokenizedFile(List<Sentence> sentences, File f) {
    Function<Sentence, String> show = s -> {
      int n = s.size();
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < n; i++) {
        if (i > 0) sb.append(" ");
        sb.append(s.getWord(i));
      }
      return sb.toString();
    };
    writeByLine(sentences, show, f);
  }

  public String evaluate(List<FNParse> gold, List<FNParse> hyp) {
    if (gold.size() != hyp.size())
      throw new IllegalArgumentException();

    // Write out needed data
    File goldTSV = new File(workingDir, "gold.elements");
    File hypTSV = new File(workingDir, "hyp.elements");
    write(gold, goldTSV, true);
    write(hyp, hypTSV, true);

    // TODO figure out relationship between these and the given dataset
    File processedFile = new File(workingDir, "sentences.all.lemma.tags");
    File tokenizedFile = new File(workingDir, "sentences.tokenized");
    /*
//    String processedfile = "/home/travis/Desktop/code/fnparse/from-CMU/naacl2012_splits/"
        + "cv.train.sentences.all.lemma.tags";
    String tokenizedfile = "/home/travis/Desktop/code/fnparse/from-CMU/naacl2012_splits/"
        + "cv.train.sentences.tokenized";
     */
    List<Sentence> sentences = DataUtil.stripAnnotations(gold);
    generateAllLemmaTagsFile(sentences, processedFile);
    generateTokenizedFile(sentences, tokenizedFile);

    // Call SEMAFOR to convert to SemEval'07 XML format
    File goldXML = new File(workingDir, "gold.elements.xml");
    File hypXML = new File(workingDir, "hyp.elements.xml");
    tsvToXmlViaSemafor(goldTSV, goldXML, processedFile, tokenizedFile);
    tsvToXmlViaSemafor(hypTSV, hypXML, processedFile, tokenizedFile);

    // Call SemEval'07 script
    File temp = new File(workingDir, "temp-for-semeval-script");
    if (!temp.isDirectory()) temp.mkdir();
    List<String> r = execAndGetResults(new String[] {
        SEMEVAL07_SCRIPT.getPath(),
        "-c", temp.getPath(),
        "-l", "-n", "-e", "-v",
        FRAMES_SINGLE_FILE.getPath(),
        RELATION_MODIFIED_FILE.getPath(),
        goldXML.getPath(),
        hypXML.getPath()
    });
    return r.get(r.size() - 1);
  }

  private void tsvToXmlViaSemafor(File tsv, File xml, File parseFile, File tokenFile) {
    LOG.info("[tsvToXmlViaSemafor] " + tsv.getPath() + " => " + xml.getPath());
    if (!tsv.isFile())
      throw new IllegalArgumentException();
    assert wcDashL(parseFile) == wcDashL(tokenFile);
    execAndGetResults(new String[] {
        SEMAFOR_PREPARE_ANNO_SCRIPT.getPath(),
        "testFEPredictionsFile:" + tsv.getPath(),
        "startIndex:0",
        "endIndex:" + wcDashL(tokenFile),
        "testParseFile:" + parseFile,
        "testTokenizedFile:" + tokenFile,
        "outputFile:" + xml.getPath()
    });
  }

  private int wcDashL(File f) {
    List<String> r = execAndGetResults(new String[] {
        "wc", "-l", f.getPath(),
    });
    return Integer.parseInt(r.get(0).split("\\s+")[0]);
  }

  private static List<String> execAndGetResults(String[] command) {
    LOG.info("exec: " + Arrays.toString(command));
    try {
      Process p = Runtime.getRuntime().exec(command);
      BufferedReader stdout =
          new BufferedReader(new InputStreamReader(p.getInputStream()));
      List<String> l = new ArrayList<>();
      while (stdout.ready()) {
        String line = stdout.readLine();
        LOG.info(line);
        l.add(line);
      }
      stdout.close();

      BufferedReader stderr =
          new BufferedReader(new InputStreamReader(p.getErrorStream()));
      while (stderr.ready())
        LOG.info(stderr.readLine());
      stderr.close();

      int r = p.waitFor();
      if (r != 0)
        throw new RuntimeException("exit value: " + r);
      return l;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void write(List<FNParse> parses, File fePredictionsFile, boolean prependTwoZeros) {
    LOG.info("writing " + parses.size() + " parses to " + fePredictionsFile.getPath());
    /*
     * # I can dump my predictions to the same format as ${fePredictionsFile}                                                                                                                                           
 47 # but i need to reverse-engineer the format from PrepareFullAnnotationXML                                                                                                                                        
 48 "4       Economy economy.n       7       economy 1       Political_region        3:4     Descriptor      5:6     Economy 7"                                                                                      
 49 appears to be                                                                                                                                                                                                    
 50 - ??? (I believe this is just the length of the sentence)                                                                                                                                                        
 51 - frame name                                                                                                                                                                                                     
 52 - LU of target                                                                                                                                                                                                   
 53 - index of target word (this can be a string like "20_21" if not just one token)                                                                                                                                 
 54 - word of target                                                                                                                                                                                                 
 55 - sentence number                                                                                                                                                                                                
 56 - (                                                                                                                                                                                                              
 57   - FE name                                                                                                                                                                                                      
 58   - FE span                                                                                                                                                                                                      
 59 )+ 
     */
    try (FileWriter fw = new FileWriter(fePredictionsFile)) {
      for (int parseNum = 0; parseNum < parses.size(); parseNum++) {
        FNParse p = parses.get(parseNum);
        Sentence s = p.getSentence();
        for (FrameInstance fi : p.getFrameInstances()) {
          Span t = fi.getTarget();
          Frame f = fi.getFrame();
          String numItems = String.valueOf(1 + fi.numRealizedArguments());
          String luStr = "foo"; // This *nearly* didn't make a difference in the evaluation script
          String luLoc = spanLocStr(t);
          String targetWord = s.getWord(t.start);  // see if this makes a difference
          assert t.start < s.size();
          if (prependTwoZeros)
            fw.write("0\t0\t");
          fw.write(String.format("%s\t%s\t%s\t%s\t%s\t%s",
              numItems, f.getName(), luStr, luLoc, targetWord, String.valueOf(parseNum)));
          int r = 1;
          for (int k = 0; k < f.numRoles(); k++) {
            Span arg = fi.getArgument(k);
            if (arg == Span.nullSpan)
              continue;
            assert arg.start < s.size();
            assert arg.end <= s.size();
            fw.write(String.format("\t%s\t%s", f.getRole(k), spanBoundStr(arg)));
            r++;
          }
          fw.write("\n");
          assert String.valueOf(r).equals(numItems);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String spanBoundStr(Span s) {
    if (s == Span.nullSpan)
      throw new IllegalArgumentException();
    if (s.width() == 1)
      return String.valueOf(s.start);
    else
      return String.format("%d:%d", s.start, s.end - 1);
  }

  /**
   * Returns the set of tokens in the span (which is assumed to be [inclusive,exclusive])
   * separated by underscores:
   * e.g. Span(15,17) => "15_16"
   */
  private static String spanLocStr(Span s) {
    if (s == Span.nullSpan)
      throw new IllegalArgumentException();
    if (s.width() == 1)
      return String.valueOf(s.start);
    StringBuilder sb = new StringBuilder();
    for (int i = s.start; i < s.end; i++) {
      if (i > s.start)
        sb.append("_");
      sb.append(String.valueOf(i));
    }
    return sb.toString();
  }
}
