package edu.jhu.hlt.ikbp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.TIOStreamTransport;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenList;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.TokenizationKind;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory.AnalyticUUIDGenerator;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;

/**
 * Writes out (trivially) sectioned and tokenized text to {@link Communication}s
 * for ingestion into concrete-stanford.
 * The parsey half of the pipeline, etc is described in data/parma/ecbplus/Makefile
 *
 * @author travis
 */
public class EcbPlusXmlToRawConcrete {
  
  private EcbPlusXmlWrapper xml;
  private Communication c;
  
  public EcbPlusXmlToRawConcrete(EcbPlusXmlWrapper xml, AnnotationMetadata meta) {
    this.xml = xml;
    AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory();
    AnalyticUUIDGenerator g = f.create();

    c = new Communication();
    c.setId(xml.getXmlFile().getName().replaceAll(".xml", ""));
    c.setUuid(g.next());
    c.setType("Story");
    c.setMetadata(meta);

    Section section = new Section();
    section.setUuid(g.next());
    section.setKind("Passage");
    c.addToSectionList(section);
    
    StringBuilder sb = new StringBuilder();
    
    for (List<String> sent : xml.getTokens2()) {
      int start = sb.length();

      Sentence sentence = new Sentence();
      sentence.setUuid(g.next());
      Tokenization tokenization = new Tokenization();
      tokenization.setKind(TokenizationKind.TOKEN_LIST);
      tokenization.setUuid(g.next());
      tokenization.setMetadata(meta);
      tokenization.setTokenList(convert(sent, start));
      sentence.setTokenization(tokenization);
      section.addToSentenceList(sentence);
      
      sb.append(StringUtils.join(" ", sent));
      int end = sb.length();
      sb.append('\n');
      sentence.setTextSpan(new TextSpan().setStart(start).setEnding(end));
    }
    
    section.setTextSpan(new TextSpan().setStart(0).setEnding(sb.length()));
    c.setText(sb.toString());
  }
  
  public Communication getCommunication() {
    return c;
  }
  
  public static TokenList convert(List<String> tokens, int characterOffsetSentenceStart) {
    TokenList tl = new TokenList();
    int start = characterOffsetSentenceStart;
    for (int i = 0; i < tokens.size(); i++) {
      int end = start + (i == 0 ? 0 : 1) + tokens.get(i).length();
      Token t = new Token()
          .setTokenIndex(i)
          .setTextSpan(new TextSpan(start, end))
          .setText(tokens.get(i));
      tl.addToTokenList(t);
      start = end + 1;
    }
    return tl;
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File outputDir = config.getOrMakeDir("output");
    String tool = config.getString("tool", "ecbplus");
    AnnotationMetadata meta = new AnnotationMetadata().setTool(tool).setTimestamp(System.currentTimeMillis() / 1000);
    EcbPlusXmlStore store = new EcbPlusXmlStore(config);
    for (File t : store.getTopicDirs()) {
      for (File f : store.getDocs(t)) {
        Log.info("converting " + f.getPath());
        EcbPlusXmlWrapper xml = store.get(f);
        EcbPlusXmlToRawConcrete x2c = new EcbPlusXmlToRawConcrete(xml, meta);
        Communication c = x2c.getCommunication();
        File commFile = new File(outputDir, c.getId() + ".comm");
        try (BufferedOutputStream b = new BufferedOutputStream(new FileOutputStream(commFile))) {
          c.write(new TCompactProtocol(new TIOStreamTransport(b)));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    Log.info("done");
  }

}
