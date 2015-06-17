package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.ingest.Ontonotes4;
import edu.jhu.hlt.concrete.ingest.conll.Conll2011;
import edu.jhu.hlt.concrete.ingest.conll.Ontonotes5;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ConcreteIO;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;

/**
 * Read in some propbank data, pass it through all the converters, and make sure
 * you get the correct data out the other end.
 *
 * @deprecated I need to use the conll-formatted-ontonotes5 instead of the
 * direct Ontonotes.
 *
 * @author travis
 */
public class PropbankDataEndToEndTest {

  public static void main(String[] args) {
    //ontonotes4_oldWay();
    ontonotes5_newWay();
  }

  public static void ontonotes5_newWay() {
    Log.info("loading the frame index");
    FrameIndex.getPropbank();

    Log.info("setting up");
    MultiAlphabet alph = new MultiAlphabet();
    ConcreteIO cio = new ConcreteIO(null, null, null);
    cio.setConstituencyParseToolname("conll-2011 parse");
    cio.setPropbankToolname("conll-2011 SRL");
    cio.setPosToolName("conll-2011 POS");
    cio.setNerToolName(null);

    Log.info("reading the instance data");
    Conll2011 skels = new Conll2011(f -> f.getName().endsWith(".gold_skel"));
    File ontonotesDir = new File("/home/travis/code/fnparse/data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations");
    //File skelsDir = new File("/home/travis/code/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data/development");
//    File skelsDir = new File("/home/travis/code/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data/");
    File skelsDir = new File("/home/travis/code/conll-formatted-ontonotes-5.0/"
        + "conll-formatted-ontonotes-5.0/data/train/"
        + "data/english/annotations/bc/cnn/00/cnn_0001.gold_skel");
    boolean debug = false;
    Ontonotes5 on5 = new Ontonotes5(skels, ontonotesDir, debug);
    int docIndex = 0;
    Iterable<Communication> comms = on5.ingest(skelsDir);
    for (Communication c : comms) {
      Document d = cio.communication2Document(c, docIndex++, alph);
      List<FNParse> parses = DataUtil.convert(d);
      for (FNParse p : parses) {
        Log.info(Describe.fnParse(p));
      }
    }
  }

  public static void ontonotes4_oldWay() {
    Log.info("setting up");
    //ConcreteIO cio = ConcreteIO.makeInstance();
    ConcreteIO cio = new ConcreteIO(null, null, null);
    cio.setConstituencyParseToolname("ontonotes4");
    cio.setPropbankToolname("ontonotes4");
    cio.setPosToolName("ontonotes4-pos");
    cio.setNerToolName(null);

    Log.info("reading");
    String baseName = "ontonotes-release-4.0/data/files/data/english/annotations/bc/cnn/00/cnn_0000";
    //String baseName = "ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations/bc/cnn/00/cnn_0000";
    baseName = "/home/travis/code/fnparse/data/" + baseName;
    Ontonotes4 on4 = new Ontonotes4(baseName, "test-document", "body");
    Communication c = on4.parse().iterator().next();

    Log.info("converting");
    int docIndex = 0;
    MultiAlphabet alph = new MultiAlphabet();
    Document d = cio.communication2Document(c, docIndex++, alph);

    List<FNParse> parses = DataUtil.convert(d);
    Log.info("read " + parses.size() + " parses");
    System.out.flush();
    System.err.flush();
    for (FNParse p : parses) {
      System.out.println(Describe.fnParse(p));
    }
  }
}
