package edu.jhu.hlt.fnparse.data;

import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.ingest.Ontonotes4;
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
 * @author travis
 */
public class PropbankDataEndToEndTest {

  public static void main(String[] args) throws Exception {
    Log.info("setting up");
    //ConcreteIO cio = ConcreteIO.makeInstance();
    ConcreteIO cio = new ConcreteIO(null, null, null);
    cio.setConstituencyParseToolname("ontonotes4");
    cio.setPropbankToolname("ontonotes4");
    cio.setPosToolName("ontonotes4-pos");
    cio.setNerToolName(null);

    Log.info("reading");
    String baseName = "ontonotes-release-4.0/data/files/data/english/annotations/bc/cnn/00/cnn_0000";
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
