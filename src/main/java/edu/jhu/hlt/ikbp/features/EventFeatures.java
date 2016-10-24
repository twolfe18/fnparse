package edu.jhu.hlt.ikbp.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.EcbPlusConcreteClusterings;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.Constituent;
import edu.jhu.hlt.tutils.Document.ConstituentItr;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.ling.Language;

/**
 * For a given situation s in the RF or ECB+ data,
 * embed it with f(s)
 * define similarity function q(v_1, v_2) on vector space
 * scan gigaword to find the situations with the most similar embeddings
 * 
 * we want to be able to extract these situations to a file, using the format:
 * 
 * 
 * 
 * 
 * @author travis
 */
public class EventFeatures {
  
  private Document doc;
  private int predicateHeadword;
  
  public List<String[]> features() {
    List<String[]> f = new ArrayList<>();
    
    // Headword
    f.add(new String[] {"hw", doc.getWordStr(predicateHeadword)});
    f.add(new String[] {"hw", doc.getAlphabet().pos(doc.getPosH(predicateHeadword))});
    
    LabeledDirectedGraph deps = doc.stanfordDepsBasic;
    LabeledDirectedGraph.Node n = deps.getNode(predicateHeadword);
    
    // Parents
    for (int i = 0; i < n.numParents(); i++) {
      String deprel = doc.getAlphabet().dep(n.getParentEdgeLabel(i));
      int head = n.getParent(i);
      f.add(new String[] {"up", deprel});
      if (head >= 0) {
        int hwi = doc.getWord(head);
        if (hwi >= 0) {
          String headword = doc.getWordStr(head);
          f.add(new String[] {"up", deprel, headword});
          String headpos = doc.getAlphabet().pos(doc.getPosH(head));
          f.add(new String[] {"up", deprel, headpos});
        }
      }
    }
    
    // Children
    for (int i = 0; i < n.numChildren(); i++) {
      String deprel = doc.getAlphabet().dep(n.getChildEdgeLabel(i));
      int child = n.getChild(i);
      f.add(new String[] {"down", deprel});
      String modifier = doc.getWordStr(child);
      f.add(new String[] {"down", deprel, modifier});
      String modifpos = doc.getAlphabet().pos(doc.getPosH(child));
      f.add(new String[] {"down", deprel, modifpos});
    }
    
    return f;
  }
  
  static class Trie<T> {
    T payload;
    int count;
    Map<T, Trie<T>> children;
    
    public Trie(T payload) {
      this.payload = payload;
      children = new HashMap<>();
    }
    
    public Trie<T> getOrMakeChild(T child) {
      Trie<T> c = children.get(child);
      if (c == null) {
        c = new Trie<>(child);
        children.put(child, c);
      }
      return c;
    }
    
    public double prob(T child, double smooth) {
      double n = smooth;
      if (children.containsKey(child))
        n += children.get(child).count;
      double z = count + smooth * children.size();
      return n / z;
    }
    
    public double prob(T[] path, double smooth) {
      double p = 1;
      Trie<T> cur = this;
      for (int i = 0; i < path.length; i++) {
        if (cur == null)
          return 0;
        p *= cur.prob(path[i], smooth);
        cur = cur.children.get(path[i]);
      }
      return p;
    }
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);

//    Predicate<Link> keep = l -> l.pair.contains("XML/dev");
//    ConcreteIkbpAnnotations labels = new RfToConcreteClusterings("rothfrank", keep, config);
    ConcreteIkbpAnnotations labels = EcbPlusConcreteClusterings.build(config);
    
    ConcreteToDocument c2d = new ConcreteToDocument(null, null, null, Language.EN);
    c2d.readConcreteStanford();
    c2d.situationMentionToolAuto = labels.getName();
    MultiAlphabet alph = new MultiAlphabet();

    EventFeatures ef = new EventFeatures();
    
    Trie<String> tr = new Trie<>(null);
    
//    Average perp = new Average.Uniform();
    Average perp = new Average.Exponential(0.95);
    
    while (labels.hasNext()) {
      Topic t = labels.next();
      for (Communication c : t.comms) {
        ef.doc = c2d.communication2Document(c, 0, alph, c2d.lang).getDocument();
        for (ConstituentItr sm = ef.doc.getConstituentItr(ef.doc.cons_situationMentions_auto); sm.isValid(); sm.gotoRightSib()) {
          Constituent pred = sm.getLeftChildC();
          if (pred.getFirstToken() == pred.getLastToken() && pred.getFirstToken() >= 0) {
            ef.predicateHeadword = pred.getFirstToken();
//            System.out.println(ef.features());
            
            Average.Uniform p = new Average.Uniform();
            for (String[] f : ef.features()) {
              System.out.print(" " + Arrays.toString(f));
              double pr = tr.prob(f, 0.03);
//              System.out.print(" " + pr);
              p.add(pr);
              
              Trie<String> cur = tr;
              cur.count++;
              for (int i = 0; i < f.length; i++) {
                cur = cur.getOrMakeChild(f[i]);
                cur.count++;
              }
            }
            perp.add(Math.log(p.getAverage()));
            System.out.println();
            System.out.println(p.getAverage() + "\t" + p.getNumObservations());
            System.out.println("perp=" + perp.getAverage());
          }
        }
      }
    }
  }
}
