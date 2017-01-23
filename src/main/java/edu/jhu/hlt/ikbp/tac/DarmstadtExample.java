package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TCompactProtocol;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.IntPair;

/**
 * Search for "Jaswant Singh" mentioned in
 * http://www.hindustantimes.com/delhi/indian-arrested-for-rape-in-germany-to-be-prosecuted-in-india/story-S64ZtA0gC5XKs0WpuGfkgK.html
 * 
 * @see /home/travis/code/fnparse/data/sit-search/darmstadt-example
 * 
 * @author travis
 */
public class DarmstadtExample {
  
  static final File EX_ROOT = new File("/home/travis/code/fnparse/data/sit-search/darmstadt-example");
  static final File EX_COMM_FILE = new File(EX_ROOT, "jaswant-singh-example-doc.txt.concrete");
  
  public static KbpQuery getQuery() throws Exception {
    Communication c = new Communication();
    TDeserializer deser = new TDeserializer(new TCompactProtocol.Factory());
    byte[] bs = Files.readAllBytes(EX_COMM_FILE.toPath());
    deser.deserialize(c, bs);
    
    Map<String, Tokenization> tokMap = AddNerTypeToEntityMentions.buildTokzIndex(c);
    assert c.getEntityMentionSetListSize() == 1;
    EntityMentionSet ems = c.getEntityMentionSetList().get(0);
    for (EntityMention em : ems.getMentionList()) {
      if (em.getText().toLowerCase().contains("singh")) {
        
        List<Integer> ts = em.getTokens().getTokenIndexList();
        
        Tokenization t = tokMap.get(em.getTokens().getTokenizationId().getUuidString());
        Token t0 = t.getTokenList().getTokenList().get(ts.get(0));
        Token tn = t.getTokenList().getTokenList().get(ts.get(ts.size()-1));
        
        int s = t0.getTextSpan().getStart();
        int e = tn.getTextSpan().getEnding();
        
//        System.out.println(em.getText() + "\t" + em.getTokens() + "\t" + t0 + "\t" + tn);
        System.out.println(em.getText() + "\t" + em.getTokens() + "\t" + new IntPair(s, e));

        KbpQuery q = new KbpQuery("darmstadt-example-1");
        q.entity_id = "Jaswant Singh";
        q.entity_type = "PER";
        q.entityMention = null;
        q.docid = EX_COMM_FILE.getPath();
        q.sourceComm = c;
        q.beg = s;
        q.end = e;
        return q;
      }
    }
    return null;
  }

}
