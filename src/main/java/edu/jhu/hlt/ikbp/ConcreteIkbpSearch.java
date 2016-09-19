package edu.jhu.hlt.ikbp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.concrete.Clustering;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * For now, this just returns all mentions in the same topic.
 * We will be evaluating lemma match or other methods ability to rank correct answers highly.
 * When we have good ML models and larger corpora, we can be more aggressive in the triage step.
 * 
 * Instance only represent the data from a single topic (a {@link Clustering} and some {@link Communication}s).
 *
 * @author travis
 */
public class ConcreteIkbpSearch implements IkbpSearch {
  public static boolean DEBUG = false;
  
  private Map<String, String> s2t;
  private Map<String, List<Communication>> t2d;
  
  @Override
  public List<Pair<Response, Adjoints>> search(Query q) {
    if (s2t == null)
      throw new IllegalStateException("you have to set the topic first");
    
    // situation mention id
    // => communication id
    // => topic id
    // => all communications in that topic
    // => filter according to query context
    // => flatMap the situationMentions in each communication
    // => wrap each situationMention in a Response
    
    Node subj = q.getSubject();
    String sitMentionUuid = subj.getId().getName();
    String topicUuid = s2t.get(sitMentionUuid);
    List<Communication> comms = t2d.get(topicUuid);
    if (DEBUG)
      Log.info("subject=" + subj.getId());
    
    Set<String> seenDocs = new HashSet<>();
    for (Id i : q.getContext().getDocuments())
      seenDocs.add(i.getName());
    
    List<Pair<Response, Adjoints>> rs = new ArrayList<>();
    for (Communication c : comms) {
      if (seenDocs.contains(c.getUuid().getUuidString()))
        continue;

      SituationMentionSet sms = c.getSituationMentionSetList().get(0);
      for (SituationMention sm : sms.getMentionList()) {
        
        // Make a node for this SituationMention
        Node anchor = new Node();
        anchor.setId(new Id()
            .setName(sm.getUuid().getUuidString()));
        anchor.addToFeatures(new Id()
            .setType(FeatureType.CONCRETE_UUID.ordinal())
            .setName(sm.getUuid().getUuidString()));
        if (DEBUG)
          Log.info("anchor=" + anchor);

        PKB d = new PKB(q.getContext());
        d.addToDocuments(new Id().setName(c.getUuid().getUuidString()));
        d.addToNodes(anchor);

        Response r = new Response();
        r.setId(new Id().setName(q.getId().getName() + "_r" + rs.size()));
        r.setAnchor(anchor.getId());
        r.setDelta(d);
        r.setScore(1);
        rs.add(new Pair<>(r, Adjoints.Constant.ZERO));
      }
    }
    
    return rs;
  }

  @Override
  public void setTopic(Topic topic) {
    s2t = new HashMap<>();
    t2d = new HashMap<>();
    String t = topic.clustering.getUuid().getUuidString();
    for (Communication d : topic.comms) {
      SituationMentionSet sms = d.getSituationMentionSetList().get(0);
      for (SituationMention sm : sms.getMentionList()) {
        Object old = s2t.put(sm.getUuid().getUuidString(), t);
        assert old == null;
      }
      List<Communication> ds = t2d.get(t);
      if (ds == null) {
        ds = new ArrayList<>();
        t2d.put(t, ds);
      }
      ds.add(d);
    }
  }

}
