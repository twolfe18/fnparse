package edu.jhu.hlt.ikbp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;

/**
 * Writes out just the tokens, iterates over all the XML files
 * rewriting each to a CoNLL file. After this you may want to
 * run data/parma/ecbplus/run-parsey.sh
 *
 * @author travis
 */
public class EcbPlusToConll {
  
  File inputXml;
  File outputConll;
  
  public EcbPlusToConll(File inputXml, File outputConll) {
    this.inputXml = inputXml;
    this.outputConll = outputConll;
  }
  
  public void run() throws IOException {
    Log.info(inputXml.getPath() + "\t=>\t" + outputConll.getPath());
    EcbPlusXmlWrapper xml = new EcbPlusXmlWrapper(inputXml);
    List<List<String>> tokens = xml.getTokens2();
    xml = null;
    
    String nil = "_"; // some places it seems like this should be a dash...
    String sep = "\t";
    try (BufferedWriter w = FileUtil.getWriter(outputConll)) {
      for (List<String> sent : tokens) {
        for (int i = 0; i < sent.size(); i++) {
          String tok = sent.get(i);
//          assert !tok.contains(sep) : "token contains separator: \"" + sep + "\" in " + inputXml.getPath();
          tok.replaceAll("\t", "TAB");
          String[] items = new String[] {
              String.valueOf(i+1),  // ID
              tok,  // FORM
              nil,  // LEMMA
              nil,  // CPOSTAG
              nil,  // POSTAG
              nil,  // FEATS
              nil,  // HEAD
              nil,  // DEPREL
              nil,  // PHEAD
              nil,  // PDEPREL
          };
          w.write(StringUtils.join(sep, items));
          w.newLine();
        }
        w.newLine();
      }
    }
  }

  public static void main(String[] args) throws IOException {
    File idir = new File("data/parma/ecbplus/ECB+_LREC2014/ECB+");
    File odir = new File("data/parma/ecbplus/ECB+_LREC2014/ECB+_plain_conll");
    for (String n : idir.list()) {
      File id = new File(idir, n);
      if (!id.isDirectory())
        continue;
      File od = new File(odir, n);
      od.mkdirs();
      for (File f : id.listFiles()) {
        if (!f.getName().endsWith(".xml"))
          continue;
        File of = new File(od, f.getName().replaceAll(".xml", ".conll"));
        EcbPlusToConll e = new EcbPlusToConll(f, of);
        e.run();
      }
    }
  }
}
