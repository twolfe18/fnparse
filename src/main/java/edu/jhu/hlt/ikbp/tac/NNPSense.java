package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.thrift.TDeserializer;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.util.TokenizationIter;

/**
 * The point of this class was to capture cases of having a rare last name which
 * makes the match score decent, but is obviously wrong due to a mistaken first name.
 * e.g. query is for q="Eloise Spooner" and r="Nathan Spooner".
 * I believe this also happens with first names (and different last names).
 * 
 * The point was to look at the p(x | parent(x)="Spooner", tag(x)="NNP")
 * a) across the entire corpus    -- this tells you if there are a lot of diff types of "Spooners" or not
 * b) for the query document      -- measure dist/divergence between b~c
 * c) for the response document
 * 
 * Oh, I think I want some sort of conditional entropy or something.
 * e.g. Suppose there was only one type of "Spooner" in the background dist, call this X
 * and now I tell you which type of "Spooner" it is the query
 * ...how much more information does the repsonse give you... NO THATS NOT RIGHT.
 * 
 * ==> OH, maybe:
 *   p(reponseCondDist | seeing query, background dist) / p(responseCondDist | seeing background dist)
 * So things which are already pretty common won't receive a high score.
 * 
 * The stuff I was screwing around with in main is for choosing the RHS's to condition on.
 * I think if I just start with parent and tag, that will hopefully get first/last names.
 * Worry about other stuff later.
 *
 * @author travis
 */
public class NNPSense {
  String text;
  String nerType;

  Counts<String> features;    // for now these will be x | parent(x).word==anchor.word && ner(x)!='O'
  
  /**
   * @param text is an entity word, e.g. "Spooner"
   * @param nerType can be null, in which case ner type constraint is not enforced
   */
  public NNPSense(String text, String nerType) {
    this.text = text;
    this.nerType = nerType;
    this.features = new Counts<>();
  }
  
  // Scan f2t, take values until you have at least 1000 comms, go retrieve them and compute stats
  public void scanAccumulo() throws Exception {
    Log.info("scanning for " + text);

    TimeMarker tm = new TimeMarker();
    Instance inst = new ZooKeeperInstance("minigrid", "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181");
    Connector conn = inst.getConnector("reader", new PasswordToken("an accumulo reader"));
    
    Set<String> commPrefixes = new HashSet<>();
    List<Range> tokUuids = new ArrayList<>();
    
    // Retrieve Tokenization which contain the anchor
    try (Scanner s = conn.createScanner(AccumuloIndex.T_f2t.toString(), new Authorizations())) {
      s.setRange(Range.exact(text));
      for (Entry<Key, Value> e : s) {
        String tokUuid = e.getKey().getColumnQualifier().toString();
        String commUuidP = AccumuloIndex.getCommUuidPrefixFromTokUuid(tokUuid);
        commPrefixes.add(commUuidP);
        tokUuids.add(Range.exact(tokUuid));
        if (commPrefixes.size() == 1000)
          break;
        if (tm.enoughTimePassed(2))
          Log.info("found " + tokUuids.size() + " toks and " + commPrefixes + " comm uuid prefixes");
      }
    }
    
    // Retrieve the IDs of the Communications which contain those Tokenizations
    Log.info("retrieving comm ids for " + tokUuids.size() + " tokenizations");
    Set<String> commUuids = new HashSet<>();
    int numQueryThreads = 12;
    try (BatchScanner bs = conn.createBatchScanner(AccumuloIndex.T_t2c.toString(), new Authorizations(), numQueryThreads)) {
      bs.setRanges(tokUuids);
      bs.setBatchTimeout(1, TimeUnit.MINUTES);
      for (Entry<Key, Value> e : bs) {
        String commUuid = e.getValue().toString();
        commUuids.add(commUuid);
        if (tm.enoughTimePassed(2))
          Log.info("found " + commUuids.size() + " comm uuids");
      }
    }
    
    // Retrieve those Communications
    // and on the fly figure out what the values are there
    Log.info("retrieving " + commUuids.size() + " communications by id");
    TDeserializer deser = new TDeserializer(SimpleAccumulo.COMM_SERIALIZATION_PROTOCOL);
    for (String commUuid : commUuids) {
      try (Scanner s = conn.createScanner(SimpleAccumuloConfig.DEFAULT_TABLE, new Authorizations())) {
        // Find comm
        s.setRange(Range.exact(commUuid));
        Iterator<Entry<Key, Value>> iter = s.iterator();
        assert iter.hasNext();
        Entry<Key, Value> e = iter.next();
        assert !iter.hasNext();
        Communication c = new Communication();
        deser.deserialize(c, e.getValue().get());
        
        // Find anchor and feature values
        scanComm(c);
      }
    }
    Log.info("done");
  }
  
  private void scanComm(Communication c) {
    for (Tokenization t : new TokenizationIter(c)) {
      List<Token> toks = t.getTokenList().getTokenList();
      for (int i = 0; i < toks.size(); i++) {
        assert i == toks.get(i).getTokenIndex();
        if (toks.get(i).getText().equalsIgnoreCase(text))
          scanTok(i, t, c);
      }
    }
  }
  
  private void scanTok(int i, Tokenization t, Communication c) {
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(t);
    TokenTagging ner = IndexCommunications.getPreferredNerTags(t);
    
    // Only allow a match if the matched token also matches on NER type
    if (nerType != null) {
      String net = ner.getTaggedTokenList().get(i).getTag();
      if (!net.equalsIgnoreCase(nerType))
        return;
    }
    
    Log.info("matched " + text + " in " + c.getId());
    for (Dependency d : deps.getDependencyList()) {
      if (d.isSetGov() && d.getGov() == i) {
        int j = d.getDep();
        String tag = ner.getTaggedTokenList().get(j).getTag();
        if (d.getEdgeType().equalsIgnoreCase("nn") || !tag.equalsIgnoreCase("O"))
          features.increment(d.getEdgeType() + "/" + t.getTokenList().getTokenList().get(j).getText());
      }
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    NNPSense s = new NNPSense("Spooner", null);
    s.scanAccumulo();
    System.out.println(s.features);
    Log.info("done");
  }

  /**
   * Perhaps I can take the 1000 most common walks out of an entity head?
   */
  public static void countCommonPathsLeavingEntities(ExperimentProperties config) throws Exception {
    Counts<String> commonWalksFromEnts = new Counts<>();
    Counts<String> ec = new Counts<>();
    TimeMarker tm = new TimeMarker();
    try (AutoCloseableIterator<Communication> iter = IndexCommunications.getCommunicationsForIngest(config)) {
      while (iter.hasNext()) {
        Communication c = iter.next();
        ec.increment("comm");
        new AddNerTypeToEntityMentions(c);
        Map<String, Tokenization> tmap = AddNerTypeToEntityMentions.buildTokzIndex(c);
        for (EntityMention em : IndexCommunications.getEntityMentions(c)) {
          
          if (!em.getEntityType().equalsIgnoreCase("PERSON"))
            continue;

          Tokenization tokz = tmap.get(em.getTokens().getTokenizationId().getUuidString());
          DependencyParse deps = IndexCommunications.getPreferredDependencyParse(tokz);
          List<TaggedToken> pos = IndexCommunications.getPreferredPosTags(tokz).getTaggedTokenList();
          int h = em.getTokens().getAnchorTokenIndex();
          String p0 = pos.get(h).getTag();
          String w0 = em.getEntityType(); //tokz.getTokenList().getTokenList().get(h).getText();
          for (Dependency d1 : deps.getDependencyList()) {
            if (d1.getDep() != h)
              continue;
            // Walk of length 1
            String p1, w1;
            String e01 = d1.getEdgeType();
            if (d1.isSetGov() && d1.getGov() >= 0) {
              p1 = pos.get(d1.getGov()).getTag();
              w1 = tokz.getTokenList().getTokenList().get(d1.getGov()).getText();
              
              for (Dependency d2 : deps.getDependencyList()) {
                if (d2.getDep() != d1.getGov())
                  continue;
                String p2, w2;
                String e12 = d2.getEdgeType();
                if (d2.isSetGov() && d2.getGov() >= 0) {
                  p2 = pos.get(d2.getGov()).getTag();
                  w2 = tokz.getTokenList().getTokenList().get(d2.getGov()).getText();
                } else {
                  p2 = "ROOT";
                  w2 = "ROOT";
                }
//                commonWalksFromEnts.increment(p0 + "-" + e01 + "-" + p1 + "-" + e12 + "-" + p2);
                commonWalksFromEnts.increment(w0 + "-" + e01 + "-" + w1 + "-" + e12 + "-" + w2);
              }
            } else {
              p1 = "ROOT";
              w1 = "ROOT";
            }
//            commonWalksFromEnts.increment(p0 + "-" + e01 + "-" + p1);
            commonWalksFromEnts.increment(w0 + "-" + e01 + "-" + w1);
          }
        }
        if (ec.getCount("comm") > 10000)
          break;
        if (tm.enoughTimePassed(2))
          Log.info("event counts: " + ec);
      }
    }
    
    int i = 0;
    for (String f : commonWalksFromEnts.getKeysSortedByCount(true)) {
      System.out.println(commonWalksFromEnts.getCount(f) + "\t" + f);
      if (++i == 100)
        break;
    }

    Log.info("done");
  }
}