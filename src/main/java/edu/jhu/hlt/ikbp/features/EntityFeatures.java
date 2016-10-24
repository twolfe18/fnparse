package edu.jhu.hlt.ikbp.features;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

import edu.jhu.hlt.acute.iterators.tar.TarGzArchiveEntryByteIterator;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.serialization.CommunicationSerializer;
import edu.jhu.hlt.concrete.serialization.CompactCommunicationSerializer;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.ConstituentItr;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.ling.Language;
import edu.jhu.hlt.utilt.AutoCloseableIterator;


public class EntityFeatures {
  
  
  
  
  
  
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    File f = new File("data/concretely-annotated-gigaword/sample-med/nyt_eng_200101.tar.gz");

//    Communication c = new Communication();
//    try (BufferedInputStream b = new BufferedInputStream(new FileInputStream(f))) {
//      c.read(new TCompactProtocol(new TIOStreamTransport(b)));
//    } catch (Exception e) {
//      throw new RuntimeException(e);
//    }
    
    ConcreteToDocument c2d = new ConcreteToDocument(null, null, null, Language.EN);
    c2d.readConcreteStanford();
    MultiAlphabet alph = new MultiAlphabet();
    
    Timer t = new Timer("doc", 300, true);
//    int nEnt = 0;
//    int nEntMent = 0;
    Counts<String> ev = new Counts<>();
    
    CommunicationSerializer ser = new CompactCommunicationSerializer();
    try (InputStream is = Files.newInputStream(f.toPath());
        BufferedInputStream bis = new BufferedInputStream(is, 1024 * 8 * 24);) {
      AutoCloseableIterator<byte[]> iter = new TarGzArchiveEntryByteIterator(bis);
      while (iter.hasNext()) {
        t.start();
        Communication c = ser.fromBytes(iter.next());
        
        ConcreteDocumentMapping cd = c2d.communication2Document(c, 0, alph, c2d.lang);
        Document d = cd.getDocument();
        for (ConstituentItr ent = d.getConstituentItr(d.cons_coref_auto); ent.isValid(); ent.gotoRightSib()) {
//          nEnt++;
          ev.increment("entity");
          
          for (ConstituentItr ment = ent.getLeftChildCI(); ment.isValid(); ment.gotoRightSib()) {
//            nEntMent++;
            ev.increment("entity/mention");
            
            assert ment.getFirstToken() >= 0;
            assert ment.getLastToken() >= ment.getFirstToken();
            int w = (ment.getLastToken() - ment.getFirstToken()) + 1;
            for (int i = 1; i < 15; i++) {
              if (w <= i)
                ev.increment("entity/mention/lte" + i);
            }
            
//            if (w <= 5) {
//              System.out.print(ment.getLhs());
//              for (int i = 0; i < w; i++) {
//                int ii = ment.getFirstToken() + i;
//                String word = d.getWordStr(ii);
//                String pos = d.getAlphabet().pos(d.getPosH(ii));
//                System.out.print(" " + word + "/" + pos);
//              }
//              System.out.println();
//            }
          }

//          if (nEnt % 100000 == 0)
//            System.out.println("nEnt=" + nEnt + " nEntMent=" + nEntMent);
          if (ev.getTotalCount() % 100000 == 0)
            System.out.println(ev);
        }

//        System.out.println(d.numTokens() + " " + d.numConstituents());
        
        t.stop();
      }
    }
  }
  
}
